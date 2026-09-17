"""Trusted worker entrypoint. Never import repository modules into this process.

All repository processes run only inside the hardened container. Reports remain
untrusted evidence; this worker is not a defense against a compromised kernel.
"""
import json
import math
import os
from pathlib import Path, PurePosixPath
import signal
import stat
import subprocess
import threading
import time
import xml.etree.ElementTree as ElementTree

MAX_REPORT = 1_000_000
MAX_CASES = 1000
WORK = Path("/work")
REPO = WORK / "repo"
TOOLS = Path("/opt/testpilot")
PYTHON = "/opt/python/bin/python"
NODE = "/opt/node/bin/node"
NODE_MODULES = "/opt/testpilot-js/node_modules"


class UnsupportedExecution(ValueError):
    """A recognized request that the installed worker cannot run faithfully."""


def read_regular(path, limit=MAX_REPORT):
    # Reports must be regular files, never links/devices/FIFOs.
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, "rb") as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_size > limit:
            raise ValueError("Invalid or oversized report")
        data = stream.read(limit + 1)
        if len(data) > limit:
            raise ValueError("Oversized report")
        return data


def parse_junit(paths):
    cases = []
    total = 0
    for path in paths:
        payload = read_regular(path)
        total += len(payload)
        if total > MAX_REPORT:
            raise ValueError("Combined reports exceed limit")
        # Force UTF-8 before checking declarations (no UTF-16/encoding bypass).
        text = payload.decode("utf-8", errors="strict")
        if "<!DOCTYPE" in text.upper() or "<!ENTITY" in text.upper():
            raise ValueError("DTD/entity declarations are prohibited")
        root = ElementTree.fromstring(text)
        if root.tag not in ("testsuite", "testsuites"):
            raise ValueError("Not a JUnit report")
        for node in root.iter("testcase"):
            state, message = "PASSED", ""
            for tag, label in (("error", "ERROR"), ("failure", "FAILED"), ("skipped", "SKIPPED")):
                child = node.find(tag)
                if child is not None:
                    state = label
                    message = child.get("message", "") + "\n" + (child.text or "")
                    break
            if not node.get("name", "").strip():
                raise ValueError("Missing test name")
            seconds = float(node.get("time", "0"))
            if not math.isfinite(seconds) or seconds < 0:
                raise ValueError("Invalid test duration")
            cases.append({"name": (node.get("classname", "") + "." + node.get("name", ""))[:512],
                          "status": state, "message": message[:4096], "seconds": seconds})
            if len(cases) > MAX_CASES:
                raise ValueError("Too many test cases")
    return cases


def parse_jest(path):
    report = json.loads(read_regular(path))
    if not isinstance(report, dict) or not isinstance(report.get("testResults"), list):
        raise ValueError("Invalid Jest report")
    cases = []
    statuses = {"passed": "PASSED", "failed": "FAILED", "pending": "SKIPPED", "todo": "SKIPPED", "disabled": "SKIPPED"}
    for suite in report["testResults"]:
        if not isinstance(suite, dict) or not isinstance(suite.get("assertionResults"), list):
            raise ValueError("Invalid Jest suite")
        for test in suite["assertionResults"]:
            if not isinstance(test, dict):
                raise ValueError("Invalid Jest case")
            name = test.get("fullName", test.get("title"))
            messages = test.get("failureMessages", [])
            if not isinstance(name, str) or not name.strip() or not isinstance(messages, list) or any(not isinstance(m, str) for m in messages):
                raise ValueError("Invalid Jest case metadata")
            if test.get("status") not in statuses:
                raise ValueError("Unknown Jest test status")
            raw_duration = test.get("duration")
            if raw_duration is not None and (isinstance(raw_duration, bool) or not isinstance(raw_duration, (int, float))):
                raise ValueError("Invalid test duration")
            duration = (raw_duration or 0) / 1000
            if not math.isfinite(duration) or duration < 0:
                raise ValueError("Invalid test duration")
            cases.append({"name": name[:512],
                          "status": statuses[test["status"]],
                          "message": "\n".join(messages)[:4096], "seconds": duration})
            if len(cases) > MAX_CASES:
                raise ValueError("Too many test cases")
    return cases


def classify(exit_code, cases, output=""):
    if exit_code != 0 and dependency_failure(output):
        return "DEPENDENCY_FAILURE"
    if exit_code != 0 and compilation_failure(output):
        return "COMPILATION_FAILURE"
    if any(case["status"] in ("FAILED", "ERROR") for case in cases):
        return "TEST_FAILURE"
    if exit_code != 0:
        return "DEPENDENCY_FAILURE" if dependency_failure(output) else "INFRASTRUCTURE_FAILURE"
    if not any(case["status"] == "PASSED" for case in cases):
        return "NO_TESTS"
    return "SUCCESS"


def dependency_failure(output):
    return any(term in output.lower() for term in (
        "modulenotfounderror", "cannot find module", "could not resolve", "could not find artifact",
        "has not been downloaded", "err_module_not_found", "no module named", "failed to resolve import"))


def compilation_failure(output):
    return any(term in output.lower() for term in (
        "syntaxerror", "transform failed", "compilation error", "unexpected token",
        "parsing error", "error ts"))


def run_command(command, deadline):
    env = {"PATH": "/opt/python/bin:/opt/node/bin:/usr/share/maven/bin:/opt/java/openjdk/bin:/usr/bin:/bin",
           "HOME": "/work", "JAVA_HOME": "/opt/java/openjdk", "LANG": "C.UTF-8", "CI": "true",
           "PYTEST_DISABLE_PLUGIN_AUTOLOAD": "1", "PYTHONDONTWRITEBYTECODE": "1"}
    process = subprocess.Popen(command, cwd=REPO, env=env, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, start_new_session=True)
    captured = bytearray()

    def drain():
        while True:
            data = process.stdout.read(8192)
            if not data:
                break
            captured.extend(data[:max(0, 65536 - len(captured))])

    reader = threading.Thread(target=drain, daemon=True)
    reader.start()
    try:
        process.wait(timeout=max(0.01, deadline - time.monotonic()))
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=2)
        raise TimeoutError("Compilation/test deadline exceeded")
    finally:
        # Also kill descendants left running after their parent exits.
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        reader.join(timeout=1)
    return process.returncode, captured.decode("utf-8", errors="replace")


def materialize(request):
    REPO.mkdir()
    seen = set()
    files = request["files"] + [{"path": t["path"], "content": t["content"]} for t in request["tests"]]
    if len(files) > 1020:
        raise ValueError("Too many files")
    for file in files:
        relative = PurePosixPath(file["path"])
        if relative.is_absolute() or ".." in relative.parts or not relative.parts or str(relative) != file["path"] or "\\" in file["path"] or file["path"] in seen:
            raise ValueError("Unsafe or duplicate source path")
        seen.add(file["path"])
        path = REPO.joinpath(*relative.parts)
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("x", encoding="utf-8") as stream:
            stream.write(file["content"])
    return [str(REPO / t["path"]) for t in request["tests"]]


def commands_for(request, tests):
    language = request["language"]
    frameworks = {"Java": {"JUnit 5 / Mockito"}, "Python": {"pytest"},
                  "JavaScript": {"Jest", "Vitest", "Vitest (proposed)"},
                  "TypeScript": {"Jest", "Vitest", "Vitest (proposed)"}}
    if language not in frameworks or request.get("framework") not in frameworks[language]:
        raise UnsupportedExecution("No installed adapter for this language/framework")
    if language == "Java":
        if not (REPO / "pom.xml").exists():
            (REPO / "pom.xml").write_bytes((TOOLS / "default-pom.xml").read_bytes())
        pom = read_regular(REPO / "pom.xml").decode("utf-8")
        if "<!DOCTYPE" in pom.upper() or "<!ENTITY" in pom.upper():
            raise UnsupportedExecution("Maven DTD/entity declarations are unsupported")
        model = ElementTree.fromstring(pom)
        if any(node.tag.split("}")[-1] == "modules" for node in model.iter()):
            raise UnsupportedExecution("Multi-module Maven execution is not supported by this worker")
        # Offline, image-owned cache; no wrapper, user settings or network bootstrap.
        base = ["mvn", "-o", "-B", "--no-transfer-progress", "-Dmaven.repo.local=/opt/m2"]
        names = [str(Path(test).relative_to(REPO / "src/test/java")).removesuffix(".java").replace("/", ".") for test in tests]
        return base + ["test-compile"], base + ["-Dtest=" + ",".join(names), "surefire:test"]
    if language == "Python":
        return [PYTHON, "-I", "-m", "compileall", "-q", str(REPO)], [
            PYTHON, "-I", "-m", "pytest", "-c", str(TOOLS / "pytest.ini"), "--noconftest", "-p", "no:cacheprovider",
            "-o", "pythonpath=/work/repo /work/repo/src", "--junitxml=/work/report.xml", *tests]
    (REPO / "node_modules").symlink_to(NODE_MODULES, target_is_directory=True)
    if language == "TypeScript":
        # Explicit CLI files: repository tsconfig/plugins are never loaded.
        compile_command = [NODE, NODE_MODULES + "/typescript/bin/tsc", "--noEmit", "--skipLibCheck",
                           "--target", "ES2022", "--module", "ESNext", "--moduleResolution", "bundler",
                           "--esModuleInterop", "--jsx", "react-jsx", *tests]
    else:
        compile_command = [NODE, str(TOOLS / "syntax.cjs"), *tests]
    if request["framework"] == "Jest":
        test_command = [NODE, NODE_MODULES + "/jest/bin/jest.js", "--config", str(TOOLS / "jest.config.cjs"),
                        "--runInBand", "--no-cache", "--json", "--outputFile=/work/report.json", "--runTestsByPath", *tests]
    else:
        # Vite writes a bundled config beside the selected file. Keep the image
        # read-only and copy only our trusted config to the bounded tmpfs.
        config = WORK / "vitest.config.mjs"
        config.write_bytes((TOOLS / "vitest.config.mjs").read_bytes())
        test_command = [NODE, NODE_MODULES + "/vitest/vitest.mjs", "run", "--config", str(config),
                        "--maxWorkers=1", "--no-file-parallelism", "--reporter=junit", "--outputFile=/work/report.xml", *tests]
    return compile_command, test_command


def main():
    result = {"outcome": "INFRASTRUCTURE_FAILURE", "exitCode": None, "output": "", "tests": []}
    try:
        request = json.loads(read_regular("/input/request.json", 12 * 1024 * 1024))
        tests = materialize(request)
        compile_command, test_command = commands_for(request, tests)
        deadline = time.monotonic() + 55
        code, output = run_command(compile_command, deadline)
        result.update(exitCode=code, output=output)
        if code != 0:
            result["outcome"] = "DEPENDENCY_FAILURE" if dependency_failure(output) else "COMPILATION_FAILURE"
        else:
            code, output = run_command(test_command, deadline)
            result.update(exitCode=code, output=(result["output"] + "\n" + output)[-65536:])
            try:
                if request["language"] == "Java":
                    cases = parse_junit(sorted((REPO / "target/surefire-reports").glob("TEST-*.xml")))
                elif request["framework"] == "Jest":
                    cases = parse_jest("/work/report.json")
                else:
                    cases = parse_junit([Path("/work/report.xml")])
                result["tests"] = cases
                result["outcome"] = ("NO_TESTS" if request["language"] == "Python" and code == 5 and not cases
                                     else classify(code, cases, output))
            except (ValueError, OSError, TypeError, KeyError, ElementTree.ParseError) as exc:
                result["outcome"] = ("DEPENDENCY_FAILURE" if code != 0 and dependency_failure(output) else
                                     "COMPILATION_FAILURE" if code != 0 and compilation_failure(output) else "INVALID_REPORT")
                result["output"] = (result["output"] + "\nReport rejected: " + type(exc).__name__)[-65536:]
    except UnsupportedExecution as exc:
        result.update(outcome="UNSUPPORTED", output=str(exc))
    except TimeoutError:
        result.update(outcome="TIMEOUT", output="Container compilation/test deadline exceeded")
    except Exception as exc:
        result.update(outcome="INFRASTRUCTURE_FAILURE", output="Worker failed: " + type(exc).__name__)
    print(json.dumps(result), flush=True)


if __name__ == "__main__":
    main()

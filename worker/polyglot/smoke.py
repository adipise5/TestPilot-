"""Real-container acceptance checks. Requires an already built image; never pulls.

Run: python3 worker/polyglot/smoke.py --image testpilot-polyglot:local
All fixture source and test code executes inside Docker, not on the host.
"""
import argparse
from datetime import datetime, timezone
import time
import json
import os
from pathlib import Path
import subprocess
import tempfile
import uuid


def request(language, framework, source, code, test_path, test_code):
    return {"language": language, "framework": framework, "sourcePath": source,
            "files": [{"path": source, "content": code}],
            "tests": [{"path": test_path, "content": test_code}]}


def fixtures():
    java = request("Java", "JUnit 5 / Mockito", "src/main/java/example/App.java",
                   "package example; public class App { public static int add(int a, int b) { return a + b; } }",
                   "src/test/java/example/AppTest.java",
                   "package example; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; "
                   "class AppTest { @Test void adds() { assertEquals(5, App.add(2, 3)); } }")
    python = request("Python", "pytest", "app.py", "def add(a, b): return a + b\n",
                     "tests/test_app.py", "from app import add\ndef test_add(): assert add(2, 3) == 5\n")
    yield "Java passes", java, "SUCCESS"
    yield "Python passes", python, "SUCCESS"
    for language, ext in (("JavaScript", "js"), ("TypeScript", "ts")):
        for framework, library in (("Jest", "@jest/globals"), ("Vitest", "vitest")):
            source = "export function add(a, b) { return a + b; }" if ext == "js" else "export function add(a: number, b: number): number { return a + b; }"
            node = request(language, framework, f"app.{ext}", source, f"__testpilot__/app.test.{ext}",
                           f"import {{ test, expect }} from '{library}'; import {{ add }} from '../app'; "
                           "test('adds', () => { expect(add(2, 3)).toBe(5); });")
            yield f"{language} {framework} passes", node, "SUCCESS"
            bad = json.loads(json.dumps(node))
            bad["tests"][0]["content"] = bad["tests"][0]["content"].replace("toBe(5)", "toBe(6)")
            yield f"{language} {framework} assertion", bad, "TEST_FAILURE"
            syntax = json.loads(json.dumps(node))
            syntax["tests"][0]["content"] += "\nconst broken = ;"
            yield f"{language} {framework} syntax", syntax, "COMPILATION_FAILURE"
    for base in (java, python):
        bad = json.loads(json.dumps(base))
        bad["tests"][0]["content"] = bad["tests"][0]["content"].replace("assertEquals(5", "assertEquals(6").replace("== 5", "== 6")
        yield f"{base['language']} assertion", bad, "TEST_FAILURE"
        syntax = json.loads(json.dumps(base))
        syntax["files"][0]["content"] += "\nnot valid syntax !"
        yield f"{base['language']} syntax", syntax, "COMPILATION_FAILURE"
    yield "Python missing dependency", request("Python", "pytest", "app.py", "import testpilot_missing_dependency\n",
                                               "tests/test_app.py", "import app\ndef test_app(): assert app\n"), "DEPENDENCY_FAILURE"
    yield "Python all skipped", request("Python", "pytest", "app.py", "value = 1\n", "tests/test_app.py",
                                        "import pytest\n@pytest.mark.skip(reason='fixture')\ndef test_skip(): assert False\n"), "NO_TESTS"
    yield "Python no tests", request("Python", "pytest", "app.py", "value = 1\n", "tests/test_app.py", "value = 1\n"), "NO_TESTS"
    yield "Isolation boundary", request("Python", "pytest", "app.py", "value = 1\n", "tests/test_isolation.py", '''import os
from pathlib import Path
import socket
import resource
import pytest

def test_container_boundary():
    assert os.getuid() == 10001
    assert not Path('/var/run/docker.sock').exists()
    assert 'TESTPILOT_HOST_ONLY' not in os.environ
    status = Path('/proc/self/status').read_text()
    assert 'NoNewPrivs:\\t1' in status
    assert 'CapEff:\\t0000000000000000' in status
    assert [p.name for p in Path('/sys/class/net').iterdir()] == ['lo']
    cgroup = Path('/sys/fs/cgroup')
    assert int((cgroup / 'memory.max').read_text()) == 768 * 1024 * 1024
    assert int((cgroup / 'memory.swap.max').read_text()) == 0
    assert int((cgroup / 'pids.max').read_text()) == 128
    quota, period = map(int, (cgroup / 'cpu.max').read_text().split())
    assert quota == period
    assert resource.getrlimit(resource.RLIMIT_NOFILE) == (512, 512)
    assert resource.getrlimit(resource.RLIMIT_FSIZE) == (16777216, 16777216)
    assert os.statvfs('/work').f_blocks * os.statvfs('/work').f_frsize <= 256 * 1024 * 1024
    assert os.statvfs('/tmp').f_blocks * os.statvfs('/tmp').f_frsize <= 64 * 1024 * 1024
    for path in ('/etc/testpilot-forbidden', '/input/request.json'):
        with pytest.raises(OSError):
            with open(path, 'w') as stream:
                stream.write('must not be writable')
    with socket.socket() as connection:
        connection.settimeout(1)
        with pytest.raises(OSError):
            connection.connect(('1.1.1.1', 443))
'''), "SUCCESS"
    unsupported = json.loads(json.dumps(java))
    unsupported["files"].append({"path": "pom.xml", "content": '<project><modules><module>child</module></modules></project>'})
    yield "Multi-module Maven unsupported", unsupported, "UNSUPPORTED"
    yield "Unsupported language", request("Ruby", "RSpec", "app.rb", "value = 1", "test_app.rb", "value = 1"), "UNSUPPORTED"
    yield "Timeout", request("Python", "pytest", "app.py", "value = 1\n", "tests/test_app.py",
                             "def test_forever():\n    while True: pass\n"), "TIMEOUT"


def execute(image, payload):
    name = "testpilot-secure-" + str(uuid.uuid4())
    with tempfile.TemporaryDirectory(prefix="testpilot-smoke-") as directory:
        mount = Path(directory) / "input"
        mount.mkdir(mode=0o755)
        request_file = mount / "request.json"
        request_file.write_text(json.dumps(payload))
        request_file.chmod(0o444)
        command = ["docker", "run", "--rm", "--pull=never", "--name", name,
                   "--label", "testpilot.secure-worker=true", "--network", "none", "--read-only",
                   "--user", "10001:10001", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                   "--memory", "768m", "--memory-swap", "768m", "--cpus", "1.0", "--pids-limit", "128",
                   "--ulimit", "nofile=512:512", "--ulimit", "fsize=16777216:16777216",
                   "--log-driver", "none", "--init", "--workdir", "/work",
                   "--tmpfs", "/work:rw,nosuid,nodev,size=268435456,uid=10001,gid=10001,mode=0700",
                   "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=67108864,uid=10001,gid=10001,mode=0700",
                   "--mount", f"type=bind,src={mount},dst=/input,readonly",
                   "--entrypoint", "/opt/python/bin/python", image, "-I", "/opt/testpilot/runner.py"]
        try:
            process = subprocess.run(command, capture_output=True, text=True, timeout=75, check=True,
                                     env={**os.environ, "TESTPILOT_HOST_ONLY": "must-not-reach-worker"})
            return json.loads(process.stdout)
        finally:
            subprocess.run(["docker", "rm", "--force", name], capture_output=True, timeout=10, check=False)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--image", default="testpilot-polyglot:local")
    parser.add_argument("--filter", default="", help="Run only labels containing this text")
    parser.add_argument("--output", type=Path, help="Save actual image identity and case evidence as JSON")
    args = parser.parse_args()
    cases = [case for case in fixtures() if args.filter.lower() in case[0].lower()]
    if not cases:
        parser.error("No matching cases")
    image_id = subprocess.run(["docker", "image", "inspect", "--format={{.Id}}", args.image],
                              capture_output=True, text=True, check=True, timeout=10).stdout.strip()
    evidence = {"image": args.image, "imageId": image_id,
                "verifiedAt": datetime.now(timezone.utc).isoformat(), "cases": []}
    failures = []
    for label, payload, expected in cases:
        started = time.monotonic()
        try:
            result = execute(args.image, payload)
        except (subprocess.SubprocessError, OSError, ValueError) as exc:
            result = {"outcome": "HARNESS_FAILURE", "output": str(exc)}
        actual = result.get("outcome")
        valid = actual == expected
        if expected == "SUCCESS":
            valid = valid and result.get("exitCode") == 0 and any(c["status"] == "PASSED" for c in result.get("tests", []))
        evidence["cases"].append({"name": label, "expected": expected, "passed": valid,
                                  "durationSeconds": round(time.monotonic() - started, 3), "result": result})
        print(f"{'PASS' if valid else 'FAIL'}: {label}: {actual}", flush=True)
        if not valid:
            failures.append(label)
            print(json.dumps(result, indent=2)[:12000], flush=True)
    evidence["passed"] = not failures
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(evidence, indent=2) + "\n")
    if failures:
        raise SystemExit("Failed container checks: " + ", ".join(failures))


if __name__ == "__main__":
    main()

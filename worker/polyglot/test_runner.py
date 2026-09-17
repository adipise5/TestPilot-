import importlib.util
import json
from pathlib import Path
from unittest.mock import patch
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("worker_runner", Path(__file__).with_name("runner.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)

class WorkerTests(unittest.TestCase):
    def test_junit_statuses_and_no_false_green(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.xml"
            report.write_text('<testsuite><testcase name="ok" time="0.1"/><testcase name="bad"><failure message="wrong"/></testcase><testcase name="skip"><skipped/></testcase></testsuite>')
            cases = runner.parse_junit([report])
            self.assertEqual([c["status"] for c in cases], ["PASSED", "FAILED", "SKIPPED"])
            self.assertEqual(runner.classify(0, cases), "TEST_FAILURE")
            self.assertEqual(runner.classify(0, []), "NO_TESTS")
            self.assertEqual(runner.classify(0, [cases[2]]), "NO_TESTS")
            self.assertEqual(runner.classify(1, [cases[0]]), "INFRASTRUCTURE_FAILURE")

    def test_rejects_xml_entities_malformed_oversized_and_symlinks(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.xml"
            for data in (b'<!DOCTYPE x [<!ENTITY a SYSTEM "file:///etc/passwd">]><testsuite>&a;</testsuite>',
                         b'<testsuite>', b'x' * (runner.MAX_REPORT + 1), '<!DOCTYPE x><testsuite/>'.encode('utf-16')):
                report.write_bytes(data)
                with self.assertRaises(Exception): runner.parse_junit([report])
            report.write_text('<testsuite/>')
            link = Path(directory) / "link.xml"
            link.symlink_to(report)
            with self.assertRaises(OSError): runner.parse_junit([link])

    def test_jest_parser_and_missing_dependency(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.json"
            report.write_text(json.dumps({"testResults": [{"assertionResults": [
                {"fullName": "adds", "status": "passed", "duration": 10}, {"fullName": "skips", "status": "pending"}]}]}))
            cases = runner.parse_jest(report)
            self.assertEqual(cases[0]["seconds"], 0.01)
            self.assertEqual(runner.classify(0, cases), "SUCCESS")
            self.assertEqual(runner.classify(1, [], "ModuleNotFoundError: no module named xyz"), "DEPENDENCY_FAILURE")

    def test_python_commands_are_explicit_and_never_install(self):
        compile_cmd, test_cmd = runner.commands_for({"language": "Python", "framework": "pytest"}, ["/work/repo/tests/test_app.py"])
        self.assertIn("compileall", compile_cmd)
        self.assertIn("--noconftest", test_cmd)
        self.assertNotIn("pip", test_cmd)
        self.assertEqual(test_cmd[-1], "/work/repo/tests/test_app.py")

    def test_invalid_jest_shapes_and_case_fields(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.json"
            for payload in ({"testResults": [None]}, {"testResults": [{}]},
                            {"testResults": [{"assertionResults": [None]}]},
                            {"testResults": [{"assertionResults": [{"status": "passed"}]}]},
                            {"testResults": [{"assertionResults": [{"fullName": "test", "status": "passed", "duration": "x"}]}]}):
                report.write_text(json.dumps(payload))
                with self.subTest(payload=payload), self.assertRaises(ValueError):
                    runner.parse_jest(report)

    def test_collection_syntax_and_dependencies_have_honest_outcomes(self):
        error = [{"status": "ERROR"}]
        self.assertEqual(runner.classify(2, error, "ModuleNotFoundError: no module named missing"), "DEPENDENCY_FAILURE")
        self.assertEqual(runner.classify(2, error, "SyntaxError: invalid syntax"), "COMPILATION_FAILURE")
        self.assertEqual(runner.classify(1, error, "fixture setup failed"), "TEST_FAILURE")
        self.assertEqual(runner.classify(0, [], "SyntaxError"), "NO_TESTS")

    def test_junit_limits_and_nameless_cases(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.xml"
            for text in ('<testsuite><testcase/></testsuite>',
                         '<testsuite><testcase name="bad" time="NaN"/></testsuite>',
                         '<testsuite>' + '<testcase name="x"/>' * 1001 + '</testsuite>'):
                report.write_text(text)
                with self.assertRaises(ValueError): runner.parse_junit([report])

    def test_all_commands_are_explicit_and_multi_module_maven_is_unsupported(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(runner, "REPO", Path(directory)):
            repo = Path(directory)
            (repo / "pom.xml").write_text('<project/>')
            compile_cmd, test_cmd = runner.commands_for({"language": "Java", "framework": "JUnit 5 / Mockito"}, [str(repo / "src/test/java/example/AppTest.java")])
            self.assertIn("-o", compile_cmd)
            self.assertIn("-Dtest=example.AppTest", test_cmd)
            (repo / "pom.xml").write_text('<project><modules><module>child</module></modules></project>')
            with self.assertRaises(runner.UnsupportedExecution):
                runner.commands_for({"language": "Java", "framework": "JUnit 5 / Mockito"}, [])
            for language, framework in (("JavaScript", "Jest"), ("TypeScript", "Vitest")):
                with patch.object(runner, "WORK", repo), patch.object(runner, "TOOLS", Path(__file__).parent):
                    compile_cmd, test_cmd = runner.commands_for({"language": language, "framework": framework}, ["/work/repo/__testpilot__/app.test.js"])
                self.assertIn("--config", test_cmd)
                self.assertTrue(all("npm" != arg and "npx" != arg for arg in compile_cmd + test_cmd))
                (repo / "node_modules").unlink()
            with self.assertRaises(runner.UnsupportedExecution):
                runner.commands_for({"language": "Ruby", "framework": "RSpec"}, [])

    def test_materialization_rejects_traversal_and_aliases(self):
        for path in ("../escape.py", "/absolute.py", "./alias.py", "a//b.py"):
            with tempfile.TemporaryDirectory() as directory, patch.object(runner, "REPO", Path(directory) / "repo"):
                with self.assertRaises(ValueError):
                    runner.materialize({"files": [{"path": path, "content": ""}], "tests": []})

if __name__ == "__main__": unittest.main()

import importlib.util
import json
from pathlib import Path
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
        compile_cmd, test_cmd = runner.commands_for({"language": "Python"}, ["/work/repo/tests/test_app.py"])
        self.assertIn("compileall", compile_cmd)
        self.assertIn("--noconftest", test_cmd)
        self.assertNotIn("pip", test_cmd)
        self.assertEqual(test_cmd[-1], "/work/repo/tests/test_app.py")

if __name__ == "__main__": unittest.main()

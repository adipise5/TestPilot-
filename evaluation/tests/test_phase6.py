import copy
import json
from pathlib import Path
import tempfile
import unittest

from evaluation.phase6 import reviewer_quality as quality
from evaluation.phase6.test_usefulness import summarize, validate_dataset


class ReviewQualityTest(unittest.TestCase):
    def setUp(self):
        self.dataset = json.loads((quality.ROOT / 'review-quality-v1.json').read_text())
        self.reports = json.loads((quality.ROOT / 'reviewer-fixture-reports.json').read_text())
        self.judgments = json.loads((quality.ROOT / 'reviewer-fixture-judgments.json').read_text())

    def test_locations_are_not_semantic_truth(self):
        result = quality.score(self.dataset, self.reports)
        self.assertEqual(5, result['groundedCandidates'])
        self.assertIsNone(result['precision'])
        self.assertIsNone(result['recall'])
        self.assertEqual('UNAVAILABLE_WITHOUT_ADJUDICATION', result['semanticQualityStatus'])

    def test_duplicate_correct_finding_does_not_inflate_recall(self):
        case = self.reports['cases'][0]
        original = next(j for j in self.judgments['judgments'] if j['caseId'] == case['caseId'] and j['candidateIndex'] == 0)
        duplicate = {**original, 'candidateIndex': len(case['findings'])}
        case['findings'].append(copy.deepcopy(case['findings'][0]))
        self.judgments['judgments'].append(duplicate)
        result = quality.score(self.dataset, self.reports, self.judgments)
        self.assertEqual(5, result['truePositives'])
        self.assertEqual(2, result['falsePositives'])
        self.assertEqual(1, result['recall'])

    def test_missing_or_duplicate_judgments_rejected(self):
        for entries in (self.judgments['judgments'][:-1], self.judgments['judgments'] * 2):
            with self.assertRaises(ValueError):
                quality.score(self.dataset, self.reports, {'judgments': entries})

    def test_missing_case_and_nonobject_findings_rejected(self):
        with self.assertRaises(ValueError):
            quality.score(self.dataset, {'cases': self.reports['cases'][:-1]})
        self.reports['cases'][0]['findings'] = [None]
        with self.assertRaises(ValueError):
            quality.score(self.dataset, self.reports)

    def test_empty_results_preserve_recall_and_unavailable_precision(self):
        for case in self.reports['cases']: case['findings'] = []
        result = quality.score(self.dataset, self.reports, {'judgments': []})
        self.assertEqual(0, result['recall'])
        self.assertIsNone(result['precision'])
        self.assertIsNone(result['groundingRate'])

    def test_judgments_are_bound_to_exact_report_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            changed = Path(directory) / 'reports.json'
            changed.write_text(json.dumps(self.reports) + '\n')
            with self.assertRaisesRegex(ValueError, 'exact reports'):
                quality.evaluate(quality.ROOT / 'review-quality-v1.json', changed, quality.ROOT / 'reviewer-fixture-judgments.json')


class UsefulnessTest(unittest.TestCase):
    success = {'outcome': 'SUCCESS', 'exitCode': 0, 'tests': [{'status': 'PASSED'}]}
    failure = {'outcome': 'TEST_FAILURE', 'exitCode': 1, 'tests': [{'status': 'FAILED'}]}

    def test_custom_generated_cases_allow_measurement_without_claiming_a_kill(self):
        case = {'id': 'actual', 'language': 'Python', 'framework': 'pytest', 'sourcePath': 'app.py',
                'source': 'def add(a,b): return a+b', 'mutant': 'def add(a,b): return a-b',
                'testPath': 'tests/test_app.py', 'generated': 'def test_vacuous(): assert True'}
        validate_dataset({'origin': 'EXPORTED_GENERATED_TEST', 'cases': [case]})
        for invalid in ({**case, 'mutant': case['source']}, {**case, 'expectedMutationScores': {'generated': True}},
                        {**case, 'generated': ''}):
            with self.assertRaises(ValueError): validate_dataset({'origin': 'fixture', 'cases': [invalid]})

    def test_strong_kills_and_weak_survives(self):
        self.assertEqual(1.0, summarize([self.success] * 2, self.failure)['seededMutationScore'])
        self.assertEqual(0.0, summarize([self.success] * 2, self.success)['seededMutationScore'])

    def test_infrastructure_and_compilation_are_not_kills(self):
        for outcome in ('COMPILATION_FAILURE', 'TIMEOUT', 'HARNESS_FAILURE', 'DEPENDENCY_FAILURE', 'NO_TESTS'):
            result = summarize([self.success], {**self.failure, 'outcome': outcome})
            self.assertEqual('UNAVAILABLE', result['mutationStatus'])
            self.assertIsNone(result['seededMutationScore'])

    def test_missing_baseline_empty_success_and_failed_baselines_are_unavailable(self):
        for baselines in ([], [self.failure], [{**self.success, 'tests': []}], [{**self.success, 'exitCode': 1}]):
            self.assertIsNone(summarize(baselines, self.failure)['seededMutationScore'])
        self.assertIsNone(summarize([self.success], {**self.success, 'tests': []})['seededMutationScore'])
        self.assertIsNone(summarize([self.success], {**self.failure, 'exitCode': 0})['seededMutationScore'])


if __name__ == '__main__': unittest.main()

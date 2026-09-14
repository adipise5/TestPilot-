from __future__ import annotations

import json
import unittest
from pathlib import Path

from evaluation.run_evaluation import (
    EVALUATION_ROOT,
    check_thresholds,
    dataset_digest,
    embedding,
    retrieval_metrics,
    validate_generated_tests,
)


class EvaluationMetricsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.dataset_path = EVALUATION_ROOT / "datasets/java-testing-v1.json"
        self.config_path = EVALUATION_ROOT / "config/regression-thresholds-v1.json"
        self.dataset = json.loads(self.dataset_path.read_text(encoding="utf-8"))
        self.config = json.loads(self.config_path.read_text(encoding="utf-8"))

    def test_dataset_is_pinned_and_all_test_levels_are_present(self) -> None:
        self.assertEqual(self.config["datasetSha256"], dataset_digest(self.dataset_path))
        self.assertEqual({"UNIT", "MODULE", "INTEGRATION"}, {
            case["level"] for case in self.dataset["cases"]
        })

    def test_retrieval_metrics_use_graded_relevance(self) -> None:
        case = {"relevance": {"best": 3, "useful": 1}}
        ranked = [
            {"document": {"id": "distractor"}},
            {"document": {"id": "best"}},
            {"document": {"id": "useful"}},
        ]
        metrics = retrieval_metrics(case, ranked, 3)
        self.assertEqual(1.0, metrics["recallAtK"])
        self.assertEqual(0.5, metrics["mrr"])
        self.assertAlmostEqual(2 / 3, metrics["contextPrecision"])
        self.assertGreater(metrics["ndcgAtK"], 0)
        self.assertLess(metrics["ndcgAtK"], 1)

    def test_hash_embedding_is_deterministic_and_dimensioned(self) -> None:
        first = embedding("division zero boundary", 32)
        second = embedding("division zero boundary", 32)
        self.assertEqual(first, second)
        self.assertEqual(32, len(first))

    def test_all_generation_fixtures_satisfy_the_schema_contract(self) -> None:
        for variant in ("no_rag", "dense_only", "hybrid", "hybrid_rerank"):
            validity, _ = validate_generated_tests(
                self.dataset, EVALUATION_ROOT / "benchmark/generated" / variant)
            self.assertEqual(1.0, validity, variant)

    def test_threshold_failures_are_reported_by_metric_path(self) -> None:
        result = {"score": {"value": 0.4}}
        failures = check_thresholds(result, {
            "minimums": {"score.value": 0.5},
            "maximums": {},
        })
        self.assertEqual(1, len(failures))
        self.assertIn("score.value", failures[0])


if __name__ == "__main__":
    unittest.main()

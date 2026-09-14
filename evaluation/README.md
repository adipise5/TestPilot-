# TestPilot Phase 7 evaluation

This directory contains the pinned, offline benchmark used to validate TestPilot's evaluation pipeline. It covers the project's three supported levels: unit, module/component, and controlled integration testing. It does not contain system or browser end-to-end tests.

## What is measured

- Retrieval: Recall@k, mean reciprocal rank, nDCG@k, context precision, and measured retrieval latency.
- Generation fixtures: schema validity, Java compilation, assertion relevance, JaCoCo line coverage and delta, seeded-defect mutation score, defect detection, and repeated-run flakiness.
- Operations: retrieved context tokens, zero-cost fixture-generator metadata, Java execution duration, test count, and per-defect evidence.

The four configurations are `no_rag`, `dense_only`, `hybrid`, and `hybrid_rerank`. The Java tests are deterministic fixture-generator outputs, not responses from a live LLM. This distinction makes the CI gate reproducible but means the published baseline must not be presented as evidence of live-model quality.

## Reproduce

From the repository root:

```bash
python -m unittest discover -s evaluation/tests -v
python evaluation/run_evaluation.py --check
```

The runner verifies the dataset SHA-256 against `config/regression-thresholds-v1.json`, copies the benchmark into temporary workspaces, executes generated JUnit tests twice, applies all three seeded defects, parses Surefire and JaCoCo evidence, and fails when a pinned threshold regresses.

Published results live in `results/phase7-baseline.json` and `results/phase7-baseline.md`. CI writes fresh results to temporary files and uploads them as a workflow artifact, so a normal verification run does not modify the checked-in baseline.

## Adding a benchmark case

1. Add the source and one narrowly defined mutant.
2. Add relevant and distracting retrieval documents with graded relevance judgments.
3. Supply deterministic generated-test fixtures for every retrieval configuration.
4. Increment the dataset version and update its pinned SHA-256.
5. Run the benchmark, review the complete evidence, and adjust thresholds only with a written reason.

Real-provider experiments must be stored separately and include provider/model, prompt version, generation parameters, token counts, configured pricing, environment, and raw run identifiers. Never mix live-provider results into the deterministic CI baseline.

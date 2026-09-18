# Phase 6 evaluation

These tools do not call an AI provider or upload data. Bundled inputs are labeled fixtures. Java context tests exercise production retrieval/validation; usefulness code runs exclusively inside the existing restricted Docker worker; quality judgments are supplied explicitly.

## Reproduce the fixtures

From the repository root, with the Phase 4 worker image already built:

```sh
./mvnw --batch-mode --no-transfer-progress -Dtest=Phase6ContextBenchmarkTest test
python3 -m unittest discover -s evaluation/tests -v
python3 evaluation/phase6/reviewer_quality.py --fixture-check --output /tmp/review-quality.json
python3 evaluation/phase6/test_usefulness.py --image testpilot-polyglot:local --output /tmp/usefulness.json
```

The Java test writes `target/phase6-context-evidence.json`. The Docker CLI must use the intended daemon. Where a VM exposes only an approved input directory, set `TMPDIR` to that directory for the usefulness harness. It never pulls an image or installs dependencies at run time. Each container uses the same offline, non-root, resource-limited boundary as `worker/polyglot/smoke.py`.

Every dataset has a sibling `.sha256` file containing the lowercase SHA-256 of its exact bytes. Change a pin only after reviewing the changed fixtures and annotations. No automatic pin repair occurs.

## Evaluate reviewer outputs

Use `review-quality-v1.json` as the dataset schema. Each case contains `id`, source `files` (`path`, `content`) and annotated `gold` findings (`id`, `kind`, severity and evidence). An empty model result must still appear in reports; silently dropping difficult cases is rejected.

Reports use the structure in `reviewer-fixture-reports.json`: `datasetSha256`, explicit `origin`, `provider`, `model`, and `cases: [{caseId, findings}]`. Copy each candidate's finding objects from the saved review JSON. Record whether candidates are raw model responses or application-accepted findings in `origin`; do not compare those populations as if they were the same. The fixture includes an intentionally invalid raw candidate to check detection. Code supplied to the reviewer must match the benchmark case's exact source.

A grounding-only run needs no semantic judgments:

```sh
python3 evaluation/phase6/reviewer_quality.py --dataset /path/review-dataset.json \
  --reports /path/model-reports.json --output /tmp/model-grounding.json
```

For semantic quality, an independent reviewer supplies the judgments schema in `reviewer-fixture-judgments.json`: exact `datasetSha256`, exact `reportsSha256`, nonempty `adjudicator`, and exactly one judgment per candidate. Each judgment includes `caseId`, zero-based `candidateIndex`, `goldId` (null for false findings), and boolean `correct`, `actionable`, `severityCorrect`. Correct matches must identify a same-kind gold item in that case. Annotators should assess whether guidance is concrete and whether severity matches the demonstrated impact. Fix disagreements and expand incomplete gold annotations before publishing scores.

```sh
python3 evaluation/phase6/reviewer_quality.py --dataset /path/review-dataset.json \
  --reports /path/model-reports.json --judgments /path/adjudications.json \
  --output /tmp/model-quality.json
```

Precision is unique correct gold matches / candidates; recall is matched gold / all gold; repeated matches count as false positives. False-discovery rate is false positives / candidates. Severity accuracy is adjudicated severity agreement / unique correct matches. Actionable rate is unique correct and actionable matches / all candidates. Kind-specific precision/recall cover improvements and good practices separately. Without adjudications, semantic metrics are unavailable (`null`). Grounding checks exact paths/line intervals/text only. Valid coordinates alone never establish a correct finding.

## Evaluate an exported generated test

Use the same per-case structure as `test-usefulness-v1.json`, replacing `strong`/`weak` with a single `generated` field containing the actual test code. Required fields are `id`, `language`, `framework`, `sourcePath`, `source`, `mutant`, `testPath`, `generated`. Dataset metadata must have a truthful `origin`; include the generating `provider` and `model` when known. The source and mutant must differ; a human must verify that the mutant compiles and changes the behavior the test ought to detect. The tool cannot prove non-equivalence.

Write and review the dataset's sibling `.sha256` pin, then run:

```sh
python3 evaluation/phase6/test_usefulness.py --dataset /path/generated-tests.json \
  --image testpilot-polyglot:local --output /tmp/generated-usefulness.json
```

A valid baseline requires actual passing test cases and exit code zero in both repetitions. A kill requires a mutant test failure with an actual failed case and nonzero exit; a surviving mutant requires a valid successful test run. Other states produce `UNAVAILABLE` and a null score. Raw results retain selected-source coverage where available. Optional `expectedMutationScores: {"generated": 0|1}` makes this a regression assertion; without it, both a kill and survival are valid measurements. Top-level `passed` means the measurement completed and supplied expectations matched, **not** that all candidate tests are useful.

One file, one supplied mutant and the worker's supported offline dependencies bound each case. Multi-file projects and broader mutation campaigns require a larger harness. Equivalent mutants, flaky behavior, incomplete annotations and small samples can all distort conclusions; report those limitations alongside scores.

# Revised Phase 4 — suggestions and test reports

Phase 4 adds a saved report to the explicit multi-language draft execution flow. It is separate from the historical LangGraph milestone numbered Phase 4. No repository-wide AI reviewer or new RAG/evaluation features are included.

## User flow

1. Open a project's multi-language test plan, generate and review a real-provider draft, then execute it in the offline container.
2. Under the completed execution, select **View test report**. Existing attempts can be loaded with **Load last execution**.
3. Review case counts, failure categories, source guidance, test-quality findings, and selected-source line coverage. Expand evidence and limitations before interpreting heuristic findings as defects.
4. Select **Download JSON report** to retain that attempt. Explicit retry replaces the saved attempt and report. Drafts executed before Phase 4 require an explicit rerun to produce a report.

Reports remain available after the project's source/catalog changes and never recompute against the new files. A new attempt freezes the selected source, generated tests, snapshot and commit metadata before launch. Source/test SHA-256 hashes and the execution start timestamp identify its inputs. Lost executions recover as infrastructure failures with the frozen context; they do not imply a source defect.

## Classification and guidance

Execution outcomes distinguish compilation, dependency resolution, test failures, no executed tests, timeout, unsupported input, invalid report, capacity, cancellation and infrastructure problems. Failing cases are further categorized by diagnostic heuristics as assertion mismatch, fixture/setup issue, unexpected exception, test timeout or unknown. Both the raw diagnostic and guidance are retained.

Source suggestions include contract review after failed assertions, a potential zero-divisor check when diagnostics support it, broad exception handling and coercive JS/TS comparisons. Test-quality checks flag constant assertions, timing-dependent tests, declared/reported skips and uncovered source lines. These are bounded lexical checks on the selected source and generated tests; they are explicitly marked `HEURISTIC`, `HYPOTHESIS`, `REVIEW_REQUIRED` or `OBSERVED`. They can match comments/strings and require review. Line references/snippets come from frozen input text, never from an LLM's invented location. No automatic edits are applied.

The older Java failure/fix agents also stop fabricating a confident root cause or an “applied fix” when their provider fails. Their fallback reports unavailable analysis with zero confidence and preserves the original source.

## Coverage evidence

| Language/framework | Tool in the offline image | Evidence |
|---|---|---|
| Java / JUnit | JaCoCo 0.8.12 agent and CLI | Executed/missing lines from the selected source's XML entry |
| Python / pytest | coverage.py 7.10.6 | Selected source's executed/missing lines from JSON |
| JS/TS / Jest | V8 coverage via Jest | Istanbul statement-start lines aggregated using maximum hit count per line |
| JS/TS / Vitest | `@vitest/coverage-v8` 3.2.4 | Same line aggregation, including coverage on failed tests |

Dependencies are installed only while building the reviewed image. Compilation, tests and coverage collection share the existing 55-second worker deadline; Docker restrictions remain unchanged. Tools report line coverage differently, so percentages across languages should not be treated as directly comparable.

Coverage/report UI applies to the explicit single-target draft flow. Legacy multi-target Java workflow metrics remain unavailable because one selected file cannot represent an entire run.

Only `MEASURED` evidence with executable lines has a percentage. `UNAVAILABLE`, `INVALID` and `NO_EXECUTABLE_LINES` have null percentages/counts. An actual measured zero is distinct from missing measurement. No branch coverage, mutation score, baseline delta or repository-wide coverage is claimed. Imported dependencies, custom Maven fork settings, stripped Java debug information, unsupported layouts and collector failures can prevent measurement; the report states this without inferring a value.

Coverage files use the worker's bounded regular-file reader (no symlinks, size cap, XML entity rejection). The host revalidates the tool, exact source path, unique/disjoint line sets and source line bounds. Tests can still forge their own in-container output; these are tool-reported observations, not tamper-proof attestation or proof of assertion usefulness.

Reference documentation: [coverage.py commands](https://coverage.readthedocs.io/en/7.10.6/cmd.html), [JaCoCo CLI](https://www.jacoco.org/jacoco/trunk/doc/cli.html), [Jest coverage configuration](https://jestjs.io/docs/configuration#collectcoveragefrom-array), [Vitest failure coverage](https://vitest.dev/config/coverage#coverage-reportonfailure).

## API and storage

Both routes require project read access; execution still requires write access.

- `GET /api/projects/{projectId}/test-drafts/{draftId}/execution/report`
- `GET /api/projects/{projectId}/test-drafts/{draftId}/execution/report/download`

The download returns `application/json`, attachment filename and `Cache-Control: no-store`. Both routes return the same frozen `testpilot-report-v1` structure: provenance, artifacts, execution, summary, failures, findings, coverage, analysis method and limitations. They cause no execution or provider call. Missing, running and legacy attempts without reports return 404 with rerun/completion guidance. Other users receive 403 and unauthenticated requests receive 401.

`draft_executions` gains nullable TEXT columns `report_context_json` and `report_json`. Existing `ddl-auto: update` development profiles add them automatically. For installations using explicit migrations, apply the following before the new server starts:

```sql
ALTER TABLE draft_executions ADD COLUMN IF NOT EXISTS report_context_json TEXT;
ALTER TABLE draft_executions ADD COLUMN IF NOT EXISTS report_json TEXT;
```

No old rows are backfilled with inferred reports. The context stores the selected source and generated tests, not a second copy of the entire repository. Keep database/report access restricted to project members because snippets and diagnostics contain project code.

## Build and verification

Rebuild the worker before using Phase 4; the previous Phase 3 image has no coverage toolchain:

```sh
docker build --tag testpilot-polyglot:local --file worker/polyglot/Dockerfile .
python3 -m unittest discover -s worker/polyglot -p 'test_*.py'
python3 worker/polyglot/smoke.py --image testpilot-polyglot:local --output /tmp/phase4-container-evidence.json
TEST_REAL_CONTAINER=true ./mvnw --batch-mode --no-transfer-progress test
npm --prefix frontend run lint
npm --prefix frontend run build
```

Real-container acceptance covers passing/failing cases for all six language/framework combinations, deliberately partial/zero/empty-source coverage, compilation failures, unsupported execution, dependency failures, skipped/empty tests, timeout and the existing resource/network restrictions. Backend tests cover frozen downloads after catalog changes, retry replacement, authorization, legacy absence, interruption recovery, classification, exact line evidence and invalid coverage rejection. CI uploads Phase 4 container evidence and repeats the real application executor test.

Local verification passed: 106 backend tests (one optional PostgreSQL test skipped), 12 worker parser/command tests, all 32 real-container cases, four workflow tests, five evaluation tests and the pinned benchmark. Frontend lint, production build and production dependency audit passed.

Local verification results are recorded in `docs/verification/phase4-summary.json` and `phase4-container-evidence.json`; Phase 3 evidence remains historical and unchanged.

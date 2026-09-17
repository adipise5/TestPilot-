# Revised project analysis — 2026-09-17

Baseline: `30031f8` on `main`. This analysis follows the requested revised phases
3–6, not the historical eight-phase roadmap. No commits or remote writes are
part of this implementation.

## Architecture and working foundations

- Spring Boot 3.2.5 / Java 17 owns JWT/RBAC, project authorization, immutable GitHub
  intake, model adapters, persistence, execution and delivery. PostgreSQL/pgvector
  is the development store; H2 is an ephemeral local/test profile.
- React provides projects, catalog selection, multilingual plans, saved drafts,
  execution results, legacy run traces, failures and delivery approval.
- The Python LangGraph service drives the older Java test-run pipeline through
  authenticated Spring tools. Draft generation/execution is a separate API and
  does not use the graph or its durable execution jobs.
- Intake resolves a commit, catalogs screened text and stores content hashes.
  Planning binds drafts to a project/content snapshot and selects Java/JUnit,
  Python/pytest or JS/TS/Jest/Vitest. Unsupported sources remain visible.
- Generation has prompt trust boundaries and heuristic output checks. Mock output
  is deliberately skipped scaffolding. These checks are not compilation or a
  proof of safety, useful assertions or source correctness.
- Existing RAG has tenant/project/commit filtering, hybrid retrieval, token
  budgets and persisted citations. Java uses symbol-aware chunks; other
  languages currently use document sections.

## Phase-by-phase gap assessment at baseline

| Requested phase | Existing implementation | Remaining work |
| --- | --- | --- |
| 3 — secure execution | `SecureContainerExecutor`, offline Docker commands, polyglot worker, draft endpoint/UI, legacy delegation; no application host execution path | Harden report/input handling; verify draft authorization, snapshot checks and persistence; exercise actual worker languages and isolation in CI; correct obsolete setup/security documentation |
| 4 — suggestions/report | Legacy Java failure/fix agents and metrics fields | Draft-linked failure taxonomy, useful source and test-quality findings, measured coverage with unavailable states, authorized downloadable versioned report |
| 5 — AI reviewer | `CodeAnalysisAgent` extracts Java structure/testing ideas | Independent repository review workflow, good practices, evidence-bearing security/performance findings, severity, actionable guidance and honest partial-review scope |
| 6 — RAG/evaluation | Scoped hybrid RAG and pinned Java fixture benchmark | Cross-file symbol relationships for supported languages, versioned language standards, evidence validation at review ingestion, reviewer precision/recall/grounding tests and multilingual test-usefulness evaluation |

## Concrete findings

The baseline Maven run had 89 tests: one controller-test failure and one optional
pgvector skip. The failed test assumed a working runner image; Phase 3 separates
API/persistence fixtures from the new real-container acceptance suite.

1. **Execution docs and CI describe an obsolete boundary.** The application
   already delegates both Java and drafts to the offline polyglot executor. README,
   worker docs and ADR 0004 still describe local H2 execution and networked
   dependency resolution. CI builds only `worker/Dockerfile`, never the image
   selected by the application.
2. **Worker results need stricter validation.** Null statuses/outcomes can throw
   during validation; malformed Jest suites and missing reports need deterministic
   outcomes. Empty/all-skipped results must never become success. Setup errors
   must not be inferred to be defects in production code.
3. **Execution support is narrower than generation support.** The image contains
   specific offline dependencies. Maven/Gradle, nested projects, framework configs,
   plugin fixtures, browser environments and arbitrary package versions are not
   interchangeable. Missing dependencies must fail visibly without installation.
4. **Draft persistence is a separate synchronous workflow.** It has uniqueness and
   optimistic locking, explicit retry and stale-run recovery, but no graph lease,
   queue or cancellation endpoint. This distinction must be tested and documented.
5. **Draft endpoints lack integration coverage.** Baseline tests cover planning
   and executor flags but not execution ownership, stale/mock refusal, revalidation
   or retrieval of persisted results.
6. **Failure fallbacks overclaim.** `FailureAnalysisAgent` substitutes an assertion
   explanation and 0.88 confidence on provider failure; `FixSuggestionAgent`
   appends a comment and claims a fix. Phase 4 must replace these with unavailable
   evidence states, never fabricated remediation.
7. **Coverage is currently unavailable at runtime.** The new legacy executor
   returns `TestExecutionMetrics.unavailable()`. Historical benchmark coverage
   is fixture evidence, not evidence for the user's current run.
8. **Code analysis is not an AI code reviewer.** The current response has issue
   strings with no validated file/line anchors, severity evidence, good-practice
   recognition or independent review lifecycle.
9. **RAG citations do not establish review correctness.** Retrieval provenance
   exists, but new reviewer findings still need exact snapshot/path/line/snippet
   validation. Benchmark fixture scores cannot establish live-model reviewer quality.

## Implementation order and exit gates

**Phase 3:** complete and validate the container boundary, explicit language
commands, bounded parsers and honest outcomes. Add real-container CI fixtures.
Stop for owner review/manual commit and push. Docker was initially absent; the
follow-up installed a restricted local VM and completed all 25 real-container
cases plus the backend regression suite. See [Phase 3 evidence](verification/phase3-summary.json).

**Phase 4:** build the report around persisted execution evidence and snapshot
identity; distinguish compile/dependency/infrastructure/test failures; preserve
unavailable coverage and model states; expose authorized downloads and UI.
Implemented in [Revised Phase 4](REVISED_PHASE_4.md): frozen draft reports,
selected-source tool coverage, bounded findings, authorized downloads and honest
provider-failure fallbacks. The numbered findings above describe the baseline.

**Phase 5:** add a separately invoked reviewer, bounded repository traversal and
coverage-of-review metadata. Persist actionable positive/negative findings,
security/performance categories and exact evidence, with authorization tests.

**Phase 6:** add multilingual symbol/context retrieval and standards; validate
citations before display; pin reviewer datasets and measure false positives,
evidence validity, defect detection, test behavior/mutation usefulness and cost.

Deployment concerns remain: reviewed schema migrations (Hibernate currently
creates/updates tables), global tenant quotas, external worker scheduling, Docker
runtime hardening, live GitHub/provider contract tests and production secrets.
These are not proof that any requested phase is already complete.

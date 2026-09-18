# TestPilot implementation roadmap

The product target is an agentic testing platform that accepts a GitHub repository instead of requiring pasted source code, generates unit, module/component, and integration tests, executes them safely, and uses measured retrieval to improve the results.

## Scope boundary

System and end-to-end testing are not part of this project. The platform will not provision a complete deployed application, exercise browser/user journeys, or validate a production-like environment. The highest supported level is integration testing across selected application components and controlled dependencies.

Every phase has a review gate. A later phase starts only after the current phase is tested, documented, committed by the repository owner, and explicitly approved.

## Progress

[Revised project analysis](docs/REVISED_PROJECT_ANALYSIS.md) records the current
implementation gaps. [Revised Phase 3](docs/REVISED_PHASE_3.md) completes the
partial offline polyglot path; all 25 real-container cases and the backend
regression suite with Docker enabled passed locally. Revised
Phase 4 adds [saved suggestions and test reports](docs/REVISED_PHASE_4.md), including selected-source coverage and authenticated JSON downloads. Phase 5 adds the [AI Code Reviewer](docs/REVISED_PHASE_5.md), with immutable reviews, checked evidence, good practices and security/performance guidance. Phase 6 remains a separate owner-reviewed change. Historical milestones below
do not imply the new report/reviewer features are implemented.

**Revised product plan:** [Phase 1 — GitHub URL and multi-language intake](docs/REVISED_PHASE_1.md)
is implemented for review. This is a new iteration, separate from the original
eight-phase milestones below. It does not imply multilingual test execution or
the new AI code reviewer is complete.

[Revised Phase 2 — multi-language planning and generation](docs/REVISED_PHASE_2.md)
is implemented for review: Java/Python/JS/TS adapters, three-level plans, unique
names, framework prompts, static checks and saved non-executable drafts. Execution
for other languages and the new reviewer remain future approved phases.

| Phase | Status |
|---|---|
| Phase 1 — reproducible foundation | Complete |
| Phase 2 — security and execution correctness | Complete |
| Phase 3 — GitHub repository intake and MCP boundary | Complete |
| Phase 4 — LangGraph agentic workflow | Complete |
| Phase 5 — isolated multi-level test execution | Complete |
| Phase 6 — production RAG | Complete |
| Phase 7 — evaluation and observability | Complete |
| Phase 8 — reviewed GitHub delivery | Implemented; awaiting owner review and final commit |

## Phase 1 — reproducible foundation

**Goal:** establish a trustworthy baseline before changing security-sensitive runtime behavior.

Deliverables:

- Strict frontend `npm ci`, lint, Tailwind compilation, production build, and zero high-severity npm audit findings.
- Pinned Node and Maven entry points.
- GitHub Actions for backend and frontend quality gates plus dependency review.
- Honest README language that distinguishes current behavior from the target system.
- Initial architecture decisions, threat model, contribution guide, and phased roadmap.

Exit gate:

- Backend test suite passes through Maven Wrapper.
- A clean frontend installation, lint, build, and audit pass.
- No claim of AST parsing, sandboxing, autonomous agents, or measured semantic RAG remains for the current implementation.

## Phase 2 — security and execution correctness

**Goal:** close critical vulnerabilities before connecting external repositories.

Deliverables:

- Public registration always assigns the safe default role.
- Central object-level authorization with cross-user tests for every ID-based endpoint.
- Canonical repository-relative path validation, symlink defenses, and upload limits.
- Maven stdout/stderr capture and explicit compile, test, timeout, and infrastructure outcomes.
- Production configuration fails without a strong JWT secret; Actuator and H2 are restricted.
- Safe problem responses and explicit prompt/data trust boundaries.

Exit gate:

- Security regression tests cover privilege escalation, IDOR, and path traversal.
- Build failures can no longer be recorded as successful runs.

## Phase 3 — GitHub repository intake and MCP boundary

**Goal:** let a user select a repository and immutable revision instead of pasting code.

Deliverables:

- `RepositoryConnector` contract independent of GitHub transport.
- GitHub MCP adapter for repository discovery and read-only metadata/content access.
- Production-ready GitHub App/REST adapter for least-privilege installation tokens and webhook events.
- Repository record containing provider, owner, name, default branch, selected commit SHA, and installation scope.
- Manifest and build-tool detection; allowlisted file catalog; secret and binary exclusion.
- Idempotent ingestion keyed by repository plus commit SHA.

Important boundary:

- MCP is used for controlled repository discovery and semantic file access.
- Test execution always uses an immutable snapshot in an isolated worker; it never executes against a mutable branch name or an MCP server's filesystem.

Exit gate:

- A repository can be connected, indexed at an exact SHA, refreshed, and disconnected.
- Access to a repository outside the installation scope is rejected and audited.

## Phase 4 — LangGraph agentic workflow

**Goal:** replace the fixed prompt sequence with an inspectable, resumable graph.

Initial graph roles:

1. Intake agent validates repository scope and build support.
2. Codebase mapper identifies modules, build descriptors, test frameworks, and changed symbols.
3. Test planner chooses relevant test levels and creates a structured plan.
4. Unit-test specialist handles isolated functions/classes.
5. Module-test specialist handles component boundaries inside one deployable module.
6. Integration-test specialist handles databases, messaging, HTTP clients, and framework wiring.
7. Test reviewer checks compilability assumptions, duplication, assertions, and risk coverage.
8. Execution coordinator submits approved suites to workers and interprets typed results.
9. Failure triage agent classifies product defects, bad tests, environment failures, and flaky behavior.
10. Report agent creates an evidence-backed final report.

Graph requirements:

- Typed shared state, explicit node inputs/outputs, conditional edges, retry budgets, timeouts, and checkpoints.
- Deterministic tools for repository reads, parsing, retrieval, execution, and metrics.
- Human approval before integration tests that require external resources or any write back to GitHub.
- No free-form agent may execute shell commands directly.

Exit gate:

- A paused workflow resumes from a checkpoint without repeating completed side effects.
- Each agent decision and tool result is traceable to a run and commit SHA.

Implementation note:

- The Python LangGraph control plane now uses typed JSON state, conditional specialist/triage routes, bounded retries for transient tool failures, SQLite checkpoints, and `interrupt`/`Command(resume=...)` approval.
- Spring persists workflow identity and every tool attempt with an input hash and stable idempotency key. Replaying a completed tool returns its stored output.
- The UI exposes the graph version, immutable revision, node trace, report, and project-authorized approve/reject controls.
- The SQLite graph checkpointer and Spring `@Async` invocation topology remain single-host development choices. Phase 5 added durable leased execution jobs, while a shared production graph checkpointer remains future deployment work.

## Phase 5 — isolated multi-level test execution

**Goal:** generate and run unit, module/component, and integration tests with correct isolation semantics.

Deliverables:

- Dedicated non-root worker image with read-only root filesystem, temporary workspace, dropped capabilities, PID/CPU/memory/file limits, and bounded logs.
- Historical design: allowlisted dependency resolution followed by offline execution. Superseded by revised Phase 3: all runtime stages are offline, using prebuilt dependencies.
- Durable job queue with leases, idempotency, cancellation, heartbeats, retries, and restart recovery.
- Test-level-specific templates and validation policies.
- Coverage and mutation-testing collection where supported.

Exit gate:

- Malicious fixtures cannot read host files, reach the network, fork indefinitely, or survive cleanup.
- Worker loss and application restart do not lose or duplicate a completed run.

Implementation note:

- Execution jobs are unique per TestRun and persist leases, heartbeats, attempts, cancellation, bounded output, typed results, coverage, and optional mutation evidence.
- The production backend materializes the screened immutable Maven catalog into a temporary workspace and runs it through the non-root worker image. Dependency resolution and offline test execution are separate constrained stages.
- Unit, module, and integration proposals have different prompt guidance and deterministic validation policies. Revised Phase 3 replaces the original worker and H2 local runner with the same offline polyglot container in every profile.

## Phase 6 — production RAG

**Goal:** retrieve the smallest authoritative and project-specific context that improves test quality.

Deliverables:

- Separate generation and embedding interfaces.
- Java symbol-aware chunks for project code and document-aware chunks for testing guidance.
- PostgreSQL/pgvector storage with tenant/project/commit filters.
- Dense plus lexical candidates, rank fusion, relevance thresholds, deduplication, reranking, and token-budget packing.
- Stable source/chunk citations shown in generation traces and reports.
- Versioned, idempotent ingestion with provenance, content hashes, framework versions, and embedding model metadata.

Exit gate:

- Cross-project retrieval is impossible by construction and tested.
- Retrieval traces reproduce the exact context used for every generated test.

Implementation note:

- Generation and embedding providers are separate. Repository code, existing tests, build manifests, and guides are chunked semantically and indexed idempotently with source/model/version provenance.
- The dev profile initializes pgvector and HNSW/full-text indexes. Dense and lexical candidates are scope-filtered, fused, thresholded, deduplicated, reranked, and packed to a token budget.
- Generated tests reference persisted retrieval traces; the API and UI expose exact packed context and stable source/chunk citations. Cross-project isolation and trace reproduction have negative tests.

## Phase 7 — evaluation and observability

**Goal:** prove that the agentic and RAG systems improve outcomes.

Deliverables:

- Versioned Java benchmark spanning all three supported test levels and representative failures.
- No-RAG, dense-only, hybrid, and hybrid-plus-reranker experiments.
- Retrieval metrics: Recall@k, MRR, nDCG, context precision, and latency.
- Generation metrics: schema validity, compile rate, assertion relevance, coverage delta, mutation score, seeded-defect detection, and flakiness.
- Operational metrics: graph/node latency, retries, worker outcomes, tokens, model cost, queue time, and trace IDs.

Exit gate:

- Published results show where RAG helps, where it does not, and the cost/latency trade-off.
- Regression thresholds run automatically against a pinned dataset and configuration.

Implementation note:

- `evaluation/` contains a SHA-256-pinned dataset with unit, module/component, and controlled H2 integration cases, four retrieval variants, executable JUnit fixtures, three seeded defects, and reviewed regression thresholds.
- The published offline baseline reports Recall@3, MRR, nDCG@3, context precision, compilation, assertion relevance, JaCoCo coverage/delta, seeded mutation detection, flakiness, latency, context tokens, and cost metadata.
- Runtime observability is persisted per TestRun: workflow/node duration and retries, queue/worker timings and outcomes, RAG latency/tokens/trace IDs, and estimated generation tokens/cost. The dashboard and authorized API expose this evidence.
- The deterministic fixture baseline proves the measurement pipeline, not live-model quality or broad generalization. Real-provider experiments must be reported separately.

## Phase 8 — reviewed GitHub delivery

**Goal:** turn accepted output into a reviewable, validated software change.

Deliverables:

- Generate a patch against the analyzed commit SHA.
- Human approval before repository writes.
- Create a branch and pull request through a least-privilege GitHub App.
- Attach generated tests, execution logs, citations, coverage/mutation changes, and limitations to the PR.
- Record reviewer, decision, validation result, timestamps, and rollback path.

Exit gate:

- Every created PR is reproducible from a TestPilot run and contains validation evidence.
- The system never writes directly to a protected/default branch.

Implementation note:

- A successful TestRun can be frozen into one immutable delivery proposal containing a deterministic new-file patch, patch SHA-256, analyzed commit, generated-file payload, validation evidence, limitations, and rollback path.
- Proposal creation is side-effect free. A project-authorized human must record an approval before the delivery endpoint can request any GitHub write credential.
- Delivery is available only for a user-bound GitHub App installation. MCP remains read-only. The app token is narrowed to one repository and requests `contents:write` plus `pull_requests:write` only for the approved delivery operation.
- GitHub delivery creates a tree and commit whose sole parent is the analyzed SHA, creates only a dedicated `testpilot/...` branch, and opens a pull request against the recorded default branch. Direct default/protected-branch writes are rejected by policy.
- The pull request includes bounded execution logs, citations, coverage and mutation evidence (and explicitly marks unavailable deltas), limitations, patch identity, and rollback instructions. Reviewer, decision, timestamps, delivery attempts, head SHA, PR identity, and failures are persisted and audited.
- Contract and integration tests cover approval-before-write, cross-project denial, MCP write denial, patch determinism, existing-file protection, repeat delivery, dedicated-branch enforcement, and cleanup after pull-request creation failure.

## Portfolio completion criteria

For SDE roles, emphasize secure execution, authorization, durable workflows, API/data design, CI/CD, observability, idempotency, and failure semantics.

For Applied AI roles, emphasize graph/tool design, retrieval experiments, grounding and citation traces, dataset construction, test-quality metrics, model/prompt versioning, and error analysis. The project should be described as applied AI engineering unless it later includes a justified trained ML model.

# TestPilot implementation roadmap

The product target is an agentic testing platform that accepts a GitHub repository instead of requiring pasted source code, generates tests at four levels, executes them safely, and uses measured retrieval to improve the results.

Every phase has a review gate. A later phase starts only after the current phase is tested, documented, committed by the repository owner, and explicitly approved.

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
7. System-test specialist produces black-box scenarios for deployed behavior.
8. Test reviewer checks compilability assumptions, duplication, assertions, and risk coverage.
9. Execution coordinator submits approved suites to workers and interprets typed results.
10. Failure triage agent classifies product defects, bad tests, environment failures, and flaky behavior.
11. Report agent creates an evidence-backed final report.

Graph requirements:

- Typed shared state, explicit node inputs/outputs, conditional edges, retry budgets, timeouts, and checkpoints.
- Deterministic tools for repository reads, parsing, retrieval, execution, and metrics.
- Human approval before expensive system tests or any write back to GitHub.
- No free-form agent may execute shell commands directly.

Exit gate:

- A paused workflow resumes from a checkpoint without repeating completed side effects.
- Each agent decision and tool result is traceable to a run and commit SHA.

## Phase 5 — isolated multi-level test execution

**Goal:** generate and run unit, module, integration, and system tests with correct isolation semantics.

Deliverables:

- Dedicated non-root worker image with read-only root filesystem, temporary workspace, dropped capabilities, PID/CPU/memory/file limits, and bounded logs.
- Network disabled by default; explicitly allowlisted dependency-resolution stage separated from test execution.
- Durable job queue with leases, idempotency, cancellation, heartbeats, retries, and restart recovery.
- Test-level-specific templates and validation policies.
- Coverage and mutation-testing collection where supported.

Exit gate:

- Malicious fixtures cannot read host files, reach the network, fork indefinitely, or survive cleanup.
- Worker loss and application restart do not lose or duplicate a completed run.

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

## Phase 7 — evaluation and observability

**Goal:** prove that the agentic and RAG systems improve outcomes.

Deliverables:

- Versioned Java benchmark spanning all four test levels and representative failures.
- No-RAG, dense-only, hybrid, and hybrid-plus-reranker experiments.
- Retrieval metrics: Recall@k, MRR, nDCG, context precision, and latency.
- Generation metrics: schema validity, compile rate, assertion relevance, coverage delta, mutation score, seeded-defect detection, and flakiness.
- Operational metrics: graph/node latency, retries, worker outcomes, tokens, model cost, queue time, and trace IDs.

Exit gate:

- Published results show where RAG helps, where it does not, and the cost/latency trade-off.
- Regression thresholds run automatically against a pinned dataset and configuration.

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

## Portfolio completion criteria

For SDE roles, emphasize secure execution, authorization, durable workflows, API/data design, CI/CD, observability, idempotency, and failure semantics.

For Applied AI roles, emphasize graph/tool design, retrieval experiments, grounding and citation traces, dataset construction, test-quality metrics, model/prompt versioning, and error analysis. The project should be described as applied AI engineering unless it later includes a justified trained ML model.

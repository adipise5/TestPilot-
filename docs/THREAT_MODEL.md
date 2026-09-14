# TestPilot threat model

Status: updated through Phase 7. Security controls are implemented for the repository, workflow, retrieval, container-execution, evaluation, and observability boundaries, but this document does not certify any particular deployment for public use.

## Assets

- GitHub installation credentials and repository contents.
- User identities, JWT signing material, and authorization decisions.
- Source code, build files, generated tests, retrieved knowledge, prompts, and model responses.
- Worker capacity, execution logs, test results, failure reports, and review decisions.
- Application host, database, network, and future container runtime.

## Trust boundaries

```mermaid
flowchart LR
    USER["Authenticated user"] --> API["Application API"]
    GH["GitHub and MCP connector"] --> API
    API --> GRAPH["LangGraph control plane"]
    GRAPH --> TOOLS["Authenticated deterministic tool API"]
    TOOLS --> API
    API --> DB["Application database"]
    API --> MODEL["External model provider"]
    API --> QUEUE["Durable leased execution jobs"]
    QUEUE --> WORKER["Constrained dependency container"]
    WORKER --> OFFLINE["Offline test container"]
    OFFLINE --> OUT["Typed results and bounded logs"]
    OUT --> API
```

All data crossing a boundary must be authenticated where applicable, authorized to a tenant/project, size limited, validated, and traceable.

## Untrusted inputs

- Repository names, branches, paths, file contents, symlinks, archives, and build descriptors.
- Java source, tests, dependencies, plugins, annotation processors, and scripts.
- Source comments, README text, issues, stack traces, knowledge documents, and retrieved chunks.
- LLM output, including file paths, commands, citations, test code, and structured fields.
- Surefire XML and subprocess stdout/stderr.
- LangGraph checkpoint state, resume decisions, node parameters, and tool responses.

Untrusted content is data, never instruction. A model response cannot authorize a repository write or directly select a host filesystem path or shell command.

## Current critical risks

| Risk | Current state | Required control |
|---|---|---|
| Host code execution | Production defaults to a constrained non-root container; H2 retains an explicit trusted local backend | Keep the local backend inaccessible in public deployments and continuously verify the worker policy |
| Path traversal | Supplied file paths reach workspace resolution | Canonical relative-path allowlist, symlink rejection, and root containment check |
| Privilege escalation | Public registration accepts a requested role | Force the public default role and protect role administration |
| Object authorization | Some ID-based reads do not verify project access | Central authorization policy and cross-user endpoint tests |
| False success | Nonzero Maven outcomes can produce no reports and still complete | Capture bounded logs/exit code and model typed terminal outcomes |
| Secret exposure | Development JWT fallback and repository/model credentials | Secret manager/environment requirements, redaction, rotation, and fail-fast production config |
| Prompt injection | Repository and knowledge text is interpolated into prompts | Trust delimiters, deterministic tools, output schemas, scoped retrieval, and human write gates |
| Cross-project RAG leakage | Dense and lexical candidates require tenant/project/commit/model scope; global guides have an explicit zero scope | Preserve mandatory filters and cross-project negative tests for every storage adapter |
| Dependency-stage egress | Maven dependencies require network access before offline execution | Route only the dependency container through a deployment-controlled allowlisted network; never attach the test container |
| Denial of service | User code and LLM calls consume CPU, memory, time, and cost | Current worker limits, timeouts, cancellation, retrieval budgets, plus deployment quotas and queue backpressure |

## GitHub and MCP rules

- Request read-only metadata/content permissions until the reviewed-delivery phase.
- Store installation identifiers, not long-lived personal access tokens.
- Resolve branch/tag input to a commit SHA before ingestion or execution.
- Re-authorize every repository operation against the installation scope.
- Exclude Git metadata, binaries, generated outputs, vendor directories, and suspected secrets from model and embedding calls.
- Treat MCP tool results as untrusted remote data and validate them through the same connector contract.
- Never expose a repository token, Docker socket, host home directory, or application environment to a test worker.

## LangGraph rules

- LangGraph controls ordering, conditional routing, retries, checkpoints, and human interrupts; it does not receive database or GitHub credentials.
- Graph nodes can call only the versioned Spring workflow-tool contract with a dedicated 32-byte-or-longer shared secret.
- Tool calls are bound to a registered TestRun, workflow thread, graph version, immutable revision, input hash, and idempotency key.
- A resumed checkpoint must replay completed tool output instead of repeating a completed side effect.
- Integration plans that identify databases, messaging, or HTTP dependencies pause before execution for a project-authorized human decision.
- Free-form model output cannot invoke a shell command or select a tool name; graph topology and tool names are code-defined.

## Execution-worker rules

The worker runs as UID/GID 10001 with a read-only root filesystem during test execution, a fresh temporary workspace, dropped Linux capabilities, `no-new-privileges`, bounded PIDs/CPU/memory/file sizes/logs, a strict wall-clock timeout, and `--network none`. Dependency resolution is a separate constrained stage. Its cache is writable only during resolution, read-only during tests, and removed with the workspace. `TEST_DEPENDENCY_NETWORK` must identify an egress-controlled network in production.

Execution jobs are unique per TestRun. Database leases, heartbeats, persisted cancellation, bounded retries, and serialized terminal results prevent duplicate completed execution and permit recovery after worker/application loss. The screened immutable catalog is hash-checked again before materialization. Neither worker stage receives GitHub/model credentials, application environment secrets, the Docker socket, or arbitrary host mounts.

## RAG rules

- Generation and embedding providers are separate; changing the embedding model creates a separate index identity.
- Every project candidate query includes tenant, project, immutable commit, and embedding-model filters before ranking.
- Global testing guidance is stored only in the explicit `tenant=0/project=0/commit=global` scope.
- Dense and lexical lists are fused and reranked only after storage-level scope filtering.
- Retrieved content remains untrusted prompt data and cannot select tools or authorize execution.
- Every generated test stores a trace ID whose record contains the exact packed context, ordered citations, query hash, retrieval configuration, and content hashes.

## Evaluation and observability rules

- The deterministic CI dataset is versioned and SHA-256 pinned; changing cases or relevance judgments requires an explicit configuration update and review.
- Regression thresholds fail closed. A result must not be improved by deleting difficult cases, weakening a seeded defect, or mixing fixture and live-provider experiments.
- Benchmark Java executes through temporary Maven workspaces and contains only repository-owned fixtures. It is not an authorization to execute arbitrary external repositories outside the Phase 5 worker boundary.
- The TestRun observability endpoint uses project read authorization. It exposes aggregate metadata and trace identifiers, not prompts, retrieved source bodies, model responses, credentials, or environment values.
- Token counts and prices are estimates unless provider-reported usage is explicitly stored; zero-cost mock runs must not be presented as production cost measurements.

## Abuse cases to test

- Absolute paths, `../`, encoded traversal, symlink escapes, case variants, and oversized file trees.
- Static initializers and tests that read files, access environment variables, call the network, spawn processes, fork recursively, allocate memory, fill disk, or loop forever.
- A developer requesting `ADMIN` and one user requesting another user's project/run/failure/chunk IDs.
- Repository instructions that ask the model to ignore policies, reveal secrets, or write to GitHub.
- Malformed/oversized Surefire XML, missing reports, compiler errors, dependency failures, and worker termination.
- Duplicate webhooks, repeated graph nodes, application restarts, lease expiry, and concurrent retries.

## Security release gate

The application must not be presented as safe for public, multi-tenant execution solely because the code contains these controls. A deployment must additionally prove its Docker/runtime policy, dependency-egress allowlist, database isolation/backups, secret handling, quotas, monitoring, and incident response. CI exercises the worker contract and project-scope negative tests.

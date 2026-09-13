# ADR 0004: Persist execution jobs and isolate repository code in containers

- Status: Accepted
- Date: 2026-09-13

## Context

Repository build descriptors, annotation processors, source code, existing tests, and generated tests are untrusted executable input. A temporary directory and process timeout do not prevent host-file access, network access, process exhaustion, or survival after an application restart. The LangGraph execution node can also be retried, so execution must not be duplicated.

## Decision

Persist one idempotent execution job per TestRun. A worker must acquire a database lease before starting, renew the lease with heartbeats, observe cancellation, and persist a complete serialized result before the graph consumes it. Expired leases return to the queue until the bounded attempt limit is exhausted. A startup scheduler recovers and dispatches queued jobs.

Production execution uses the dedicated `worker/Dockerfile` image as a non-root user. Dependency resolution is a separate constrained container stage on a configured egress-controlled Docker network. The actual compile/test/mutation stage is offline with `--network none`, a read-only root filesystem, no Linux capabilities, `no-new-privileges`, PID/CPU/memory/file limits, a bounded wall-clock timeout, bounded logs, and per-run workspace/cache cleanup. The worker receives only the screened immutable catalog and generated tests; it never receives repository credentials, application secrets, the Docker socket, or host home mounts.

The H2 profile retains an explicit local Maven backend for trusted development and deterministic application tests. It is not a security boundary.

## Consequences

- Completed execution is replayable after graph retries or application failure without running the same TestRun twice.
- Cancellation and worker loss have persisted semantics rather than relying on an HTTP connection.
- Connected repositories execute their screened catalog and original root Maven manifest at the pinned commit; catalog hashes are revalidated before materialization.
- Docker availability and the egress policy of `TEST_DEPENDENCY_NETWORK` are deployment responsibilities.
- Gradle-only repositories are detected but the isolated execution backend currently reports them as unsupported; the current worker requires a root Maven `pom.xml`.

# ADR 0008: One offline container boundary for all application test execution

- Status: Implemented and locally verified; 25 real-container cases passed
- Date: 2026-09-17
- Supersedes: ADR 0004's local backend and networked dependency-resolution decisions

## Context

The revised product supports multilingual saved drafts. A profile-specific local
runner would undermine the container-only requirement. Executing repository build
scripts during a networked dependency stage also permits arbitrary build code to
use that network. The partial implementation at `30031f8` already replaced both
paths with an offline polyglot worker, but documentation and CI had not caught up.

## Decision

Use the same fixed Docker policy for all profiles and both execution APIs. Install
reviewed language tooling at image build time. Runtime workers never pull images,
install packages, access networks or fall back to host execution. All writable
repository storage lives in bounded container tmpfs. Source enters only as a
bounded read-only JSON snapshot. Retain the older durable Java job lifecycle;
new draft executions are explicit synchronous attempts with optimistic locking,
stale-attempt recovery and separate persisted results.

Return explicit unsupported/dependency/no-tests/report outcomes instead of
fabricating success. Treat compiler output, tests and reports as untrusted and
bound reads, parsing, runtime and output. Keep measured coverage unavailable
until actual collection is implemented. CI must build and exercise the exact
polyglot image selected by the application.

## Consequences

- Docker is required even with H2. Only trusted application/benchmark fixtures
  may execute locally as development tests; arbitrary project code cannot.
- Dependency compatibility is intentionally narrower. An operator must build a
  reviewed image with additional dependencies; no automatic bootstrap occurs.
- Fixed image-owned Python/Node configs do not honor arbitrary project settings.
  Multi-module Maven is explicitly unsupported.
- Resource limits are fixed in `OfflineContainerCommands`; old configuration
  knobs that no longer affected the worker have been removed.
- Containers reduce risk but are not a VM boundary or proof of truthful reports.
  Production still needs runtime hardening, tenant quotas and operational cleanup.
- Draft attempts are not a distributed leased queue and retain only the latest
  explicit attempt. A queue/history/cancellation feature needs a later design.

# ADR 0003: Put LangGraph behind an idempotent tool boundary

- Status: Accepted
- Date: 2026-09-12

## Context

Spring Boot is TestPilot's authenticated system of record, while Python has the strongest LangGraph ecosystem. Moving users, repository credentials, persistence, and execution into a Python process would duplicate authorization and weaken the established boundaries. Keeping a fixed Spring `@Async` sequence would not provide explicit graph state, conditional routing, durable checkpoints, or human interrupt/resume behavior.

## Decision

Run LangGraph as a separate Python control-plane service behind a versioned internal HTTP contract. The graph stores JSON-only state in a SQLite checkpointer for local development, keyed by a stable TestRun thread ID. Spring invokes start/resume operations asynchronously. LangGraph calls only allowlisted Spring workflow tools.

Every Spring tool call supplies the TestRun ID, thread ID, graph version, stable idempotency key, and graph state. Spring authenticates the call with a dedicated shared secret, revalidates the registered workflow identity, hashes the input, records the attempt, and replays the stored output for an identical completed request. Tool names are selected by compiled graph topology, not model output.

The graph contains intake, codebase mapper, test planner, unit/module/integration specialists, test reviewer, human approval, execution coordinator, failure triage, and report nodes. Conditional edges skip irrelevant specialists and failure triage. Plans involving controlled external resources interrupt before execution and resume only after an authorized project owner approves. Rejecting a plan produces a terminal report without calling execution.

## Consequences

- Restarting the LangGraph service can resume a persisted thread using the same checkpoint database.
- Completed Spring side effects are not repeated when a tool request is replayed.
- Spring and LangGraph traces identify the TestRun, graph version, node, attempt, input hash, and immutable revision.
- The local SQLite checkpointer is appropriate for development and demonstration; a shared production graph checkpointer remains deployment work.
- Phase 5 subsequently moved execution behind persisted leases and a constrained container worker; see ADR 0004.

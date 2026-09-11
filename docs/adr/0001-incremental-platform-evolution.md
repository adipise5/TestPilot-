# ADR 0001: Evolve the existing application incrementally

- Status: Accepted
- Date: 2026-09-11

## Context

The repository already contains a Spring Boot API, relational domain model, React UI, prompt components, retrieval scaffold, Maven executor, and application tests. The target solution adds GitHub repository intake, LangGraph orchestration, isolated workers, production retrieval, and evaluation.

Rewriting the product in one step would discard working behavior and make regressions difficult to attribute. Keeping every future component inside the Spring process would also couple request handling, graph execution, model dependencies, and untrusted builds too tightly.

## Decision

Retain Spring Boot as the system-of-record API for users, projects, repository connections, runs, authorization, and review decisions. Improve its security and data contracts first.

Introduce LangGraph later as a separately deployable orchestration component behind a versioned internal contract. Introduce test execution as an independent worker boundary. Components may begin in one local development topology, but their contracts must not assume a shared filesystem or process.

## Consequences

- Existing REST/UI behavior can be hardened and tested incrementally.
- Java remains the product API language while Python can be used where LangGraph has the strongest ecosystem support.
- Workflow and worker calls require typed, versioned messages and idempotency keys.
- Local development has more components in later phases, so Compose and operational documentation become necessary.
- “Agent” code in the current Spring application remains a fixed prompt pipeline until the LangGraph phase is complete.

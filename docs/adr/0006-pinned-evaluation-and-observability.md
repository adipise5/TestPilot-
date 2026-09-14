# ADR 0006: Pin an offline benchmark and persist run observability

Status: accepted

## Context

TestPilot had retrieval, generation, workflow, and execution features but no measured evidence that RAG improved test outcomes. Comparing live model responses in CI would be expensive, nondeterministic, and vulnerable to provider/model drift. Runtime timings and token/cost estimates were also scattered across logs or unavailable to an authorized reviewer.

## Decision

Maintain a versioned, SHA-256-pinned Java benchmark spanning unit, module/component, and controlled H2 integration cases. Compare no-RAG, dense-only, hybrid, and hybrid-plus-reranker retrieval. Execute deterministic generated-test fixtures against baseline and seeded-defect code, parse Surefire and JaCoCo artifacts, repeat clean runs for flakiness, and enforce reviewed thresholds in CI.

Persist operational evidence for real TestPilot runs. Workflow/node duration and retries derive from workflow records; queue/worker timing and outcomes derive from execution jobs; query/context tokens, embedding model/cost, latency, candidate counts, and trace IDs derive from RAG traces; generation calls persist estimated tokens, configured model pricing, latency, success, and operation. The project-authorized observability API exposes metadata and aggregates, not prompts or model responses.

## Consequences

- CI detects regressions against an immutable dataset and configuration.
- The checked-in baseline is reproducible without a model credential and costs no API money.
- Results can show meaningful differences between retrieval strategies while remaining explicit that handcrafted deterministic fixtures do not measure live-LLM quality or generalization.
- Token counts and costs are estimates. Accurate provider-reported usage requires a later provider adapter enhancement and separate real-provider experiments.
- The small benchmark establishes evaluation infrastructure, not statistical significance. Dataset expansion and external validity remain ongoing work.

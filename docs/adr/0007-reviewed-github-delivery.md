# ADR 0007: Separate reviewed GitHub delivery from repository ingestion

Status: accepted

## Context

TestPilot reads an immutable GitHub revision, generates and executes tests, and stores retrieval and validation evidence. Publishing those tests is materially more privileged than reading a repository. Letting an LLM, LangGraph node, MCP connector, or successful execution directly write code would collapse the trust boundary and make a mutable branch—not the analyzed commit—the effective source of truth.

## Decision

Delivery is a separate Spring-owned workflow after successful execution. Proposal creation performs no external write. It validates that the workflow and isolated execution completed successfully, reloads the exact catalog for the workflow commit, refuses to overwrite an existing test path, normalizes generated files, creates a deterministic unified patch, and persists its SHA-256, file payload, PR evidence, limitations, and rollback path.

A project-authorized human must approve the frozen proposal. Only the explicit delivery endpoint can then request a short-lived GitHub App installation token narrowed to the recorded repository with `contents:write` and `pull_requests:write`. GitHub MCP remains read-only.

The adapter creates a Git tree and commit whose parent is the analyzed SHA, creates a new `testpilot/...` ref, and opens a pull request against the recorded default branch. It never updates that default branch ref. If PR creation fails after branch creation, the adapter attempts to delete only the branch it created. Repeated delivery of a persisted successful record returns the stored PR instead of repeating the side effect.

The PR body includes run and patch identity, generated files, RAG citations, bounded execution logs, coverage/mutation evidence, explicit unavailable deltas, limitations, and rollback instructions. The database records the reviewer, decision, validation and delivery timestamps, attempts, head SHA, PR identity, and failures; repository audit events record proposal, review, and delivery actions.

## Consequences

- A generated test cannot authorize its own publication.
- Every PR is traceable to one TestRun, analyzed commit, validated file set, and approved patch hash.
- Installation permissions may include write capability, but TestPilot requests it only for the approved one-repository delivery path.
- Existing repository tests are not automatically modified; collisions require a new reviewed design rather than silent overwrite.
- Coverage and mutation deltas are marked unavailable when the worker collected only post-generation values. They must not be inferred from unrelated runs.
- A crash between an external GitHub side effect and local completion can still require audit-guided recovery. The deterministic branch name, stored rollback path, compensation attempt, and GitHub branch protections limit the impact; production-grade reconciliation remains deployment hardening.

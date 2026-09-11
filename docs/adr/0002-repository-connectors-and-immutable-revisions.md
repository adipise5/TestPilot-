# ADR 0002: Use repository connectors and immutable revisions

- Status: Accepted
- Date: 2026-09-11

## Context

The target experience accepts a GitHub repository rather than requiring users to paste source files. MCP is useful for repository discovery and controlled semantic reads, while build execution requires a complete, reproducible snapshot. Branch names are mutable and personal access tokens are difficult to scope and rotate safely.

## Decision

Define a provider-neutral repository connector contract. Implement a GitHub MCP adapter for discovery and read-only content access, and a GitHub App/REST adapter for production installation authorization, webhooks, and later reviewed write operations.

Resolve every selected branch or tag to an immutable commit SHA. Index and execute only that revision. An isolated worker receives a staged snapshot plus metadata; it never receives MCP credentials, GitHub credentials, or access to the application filesystem.

## Consequences

- Agent workflows do not depend directly on one MCP server or GitHub SDK.
- A run is reproducible and can identify repository drift.
- MCP does not become the execution security boundary.
- The platform must manage connector capabilities, installation scope, token expiry, snapshots, and webhook idempotency.
- Later pull-request creation requires a separate human-approved write capability.

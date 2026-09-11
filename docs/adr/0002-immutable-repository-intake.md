# ADR 0002: Immutable repository intake behind a connector contract

- Status: Accepted
- Date: 2026-09-12

## Context

TestPilot originally accepted pasted Java source. The target product must read a user-authorized GitHub repository without coupling ingestion, later LangGraph tools, or tests to one transport. A branch name is mutable, repository content may be hostile, and a server-owned GitHub credential must never let one TestPilot user claim another customer's installation.

## Decision

All repository reads use the `RepositoryConnector` contract. A connector supplies repository metadata, resolves a requested ref to a full commit SHA, lists that immutable tree, and reads individual files at the same SHA.

Two GitHub adapters implement the contract:

- `GITHUB_APP_REST` is the multi-user production path. A one-time state binds a GitHub App installation callback to the authenticated TestPilot user. Installation tokens are short lived, request read-only Contents permission, and are narrowed to the selected repository.
- `GITHUB_MCP` is a controlled read path. It uses the official MCP Java SDK and GitHub repository read tools. A server-side `owner/repository` allowlist is checked before any MCP call because a shared MCP token is not a tenant-identity mechanism.

An ingestion is uniquely identified by connected repository and commit SHA. Tree entries pass a strict repository-relative policy. Only Java source, existing Java tests, and Maven/Gradle build descriptors are candidates. Dependency/output directories, credential filenames, non-UTF-8 or binary data, credential-like content, oversized files, and oversized catalogs are excluded. Persisted artifacts include source object SHA and SHA-256 content hashes.

Signed GitHub webhooks revoke installation grants and disconnect repositories removed from scope. Push events are audited but do not silently move the selected commit. Refresh is explicit and again resolves to an immutable SHA.

## Consequences

- LangGraph nodes can depend on deterministic repository tools rather than GitHub-specific clients.
- Repeating ingestion for the same repository and SHA returns the existing completed result without duplicate file reads or rows.
- Repository access can be audited with actor, installation, coordinates, action, outcome, and immutable revision.
- MCP is not treated as an execution filesystem. Test execution will consume an immutable snapshot in the isolated-worker phase.
- Live GitHub App configuration and provider sandbox validation are deployment responsibilities; offline contract and integration tests use deterministic connector doubles.

# ADR 0005: Use versioned project-scoped hybrid retrieval with exact traces

- Status: Accepted
- Date: 2026-09-13

## Context

The original retrieval scaffold stored comma-separated embeddings and searched every chunk in application memory. Chunks had no tenant, project, commit, symbol, model, or ingestion identity. That design could leak code across projects and could not reproduce which context influenced a generated test.

## Decision

Separate text generation from embedding generation. Ingest repository code, existing tests, build descriptors, and testing guidance as versioned documents. Java is chunked at type and method boundaries; prose/build guidance is chunked by document section. Documents and chunks record tenant, project, immutable commit, source, line range, content hash, framework metadata, embedding model, and ingestion version. Stable chunk keys make retries idempotent.

PostgreSQL uses pgvector for dense cosine candidates and PostgreSQL full-text search for lexical candidates. Both queries require tenant/project/commit/model filters. Reciprocal-rank fusion combines the candidate lists, a deterministic heuristic reranker adds lexical and symbol evidence, a relevance threshold removes weak candidates, content hashes deduplicate results, and a token budget packs the final context. Global testing guides use the explicit `tenant=0/project=0/commit=global` scope and may be combined with one authorized project scope; another project's chunks are never eligible.

Every retrieval persists its bounded query, query hash, configuration, exact packed context, ordered citations, and token count. Generated tests store the retrieval trace ID, and reports/UI expose stable `rag://` citations.

## Consequences

- A generated test can be traced back to the exact context and immutable source lines used to generate it.
- Re-indexing unchanged content with the same model and ingestion version does not duplicate documents or chunks.
- Changing the embedding model or ingestion version creates a separate reproducible index version.
- H2 uses the same scope and ranking rules with deterministic in-process cosine/lexical scoring for tests; the dev profile uses pgvector.
- Phase 7 must measure retrieval and generation quality before claiming that RAG improves test effectiveness.

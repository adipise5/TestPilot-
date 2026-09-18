# Revised Phase 5 — AI Code Reviewer

Phase 5 adds a separately invoked, saved code-review flow over the immutable intake catalog (or manually added Java sources). It recognizes good practices, proposes improvements and shows exact source locations with implementation guidance. The legacy Java testing-analysis button remains separate.

## Use the reviewer

1. Configure a real generation provider with the existing `AI_PROVIDER`, `AI_API_KEY`, `AI_MODEL` and optional `AI_BASE_URL` settings. `AI_PROVIDER=openai` selects the existing OpenAI-compatible client; choose a model available to your account. No credential is stored in a review.
2. Connect/refresh a repository or add Java sources. In project details, select **AI Code Reviewer → Load scope and history** to inspect eligible/excluded files, provider and snapshot identity.
3. Select **Start code review**. This is synchronous, with at most eight provider calls (approximately six minutes). One request runs per application instance; overlapping requests receive a retry message.
4. Inspect good practices and improvements, filter by kind/category/severity, and review the source snippets and implementation/preservation guidance.
5. For larger catalogs, select **Review next batches**. Each request produces an independent immutable report with its batch offset. Earlier reports are retained, not silently merged. Deferred files in one report are not claimed as reviewed even if another report reviewed them.
6. Reload history or download JSON. Refresh scope after catalog updates to identify old reviews; downloads never recompute against changed files.

Mock mode records `UNAVAILABLE` with no AI findings. Provider errors/malformed responses never become a fabricated static review or a clean result. Review performs no source edits, execution, GitHub writes or automatic provider calls on reads.

## Findings and evidence

| Field | Contract |
|---|---|
| Kind | `GOOD_PRACTICE` or `IMPROVEMENT` |
| Category | `SECURITY`, `PERFORMANCE`, `CORRECTNESS`, `MAINTAINABILITY`, `TESTABILITY` |
| Severity | `CRITICAL`, `HIGH`, `MEDIUM`, `LOW`, `INFO`; good practices require `INFO` |
| Explanation | Concrete behavior/scenario/impact with uncertainty |
| Guidance | Implementation steps and validation, or how to preserve the practice |
| Evidence | Exact path, 1-based start/end lines and original text; newline styles normalized to LF |

Prompts explicitly cover injection, authorization, unsafe deserialization, secret exposure/path handling, unbounded work, repeated I/O, allocations and resource management, plus supported positive practices. These categories do not guarantee detection or establish measured performance.

The server requires evidence paths to belong to the supplied batch and to the provider's acknowledged reviewed files. Every span must match frozen source exactly and stay within real source lines. Limits: 30 findings per batch, three spans per finding, 20 lines / 8,000 characters per span, 200-character title, 3,000-character explanation/guidance. Unsupported enums, absent guidance, invented files/lines, mismatched snippets and unacknowledged files are rejected and counted. Exact duplicates are removed; invalid batch shapes fail the batch. Rejected output is not returned as a finding.

**Valid source citations prove location, not the correctness of the AI's reasoning, severity or fix.** Findings are proposals for human review. Empty results or `COMPLETED` are not security/correctness guarantees. Semantic reviewer evaluation and related-symbol retrieval remain Phase 6 work.

## Scope and budgets

- Scope is the intake catalog, not a fresh clone or unrestricted filesystem traversal. Remote files excluded during intake are outside this review.
- Supported source/test languages: Java, Python, JavaScript (`js/jsx/mjs/cjs`) and TypeScript (`ts/tsx`). Other languages, documentation and manifests are explicitly excluded in Phase 5.
- Maximum catalog: 1,000 files / 12 million characters. Connected repositories require completed intake at the selected commit and matching content hashes. Invalid/duplicate paths fail closed. A connected repository never falls back to unrelated manual sources.
- Protected/sensitive paths are never sent to the provider. Content-based secret detection/redaction is not implemented; starting a review sends supported source to the configured provider.
- Empty files and files above 30,000 characters or 2,000 lines are excluded, not silently truncated.
- Deterministic batches contain at most 20 files / 40,000 source characters. A request processes up to eight batches; `batchOffset`, `nextBatchOffset` and `totalBatches` support explicit continuation.
- Source is JSON-encoded inside the existing untrusted-data prompt boundary. The model has no execution/editing tools. Related files may land in different batches; no RAG retrieval or repository-wide call graph is claimed.
- The shared OpenAI-compatible HTTP client now has a 45-second request timeout. Configure application/proxy request timeouts to accommodate the full review window.

## States and storage

`code_reviews` stores project/snapshot identity, optimistic-lock version, status, timestamps and frozen report JSON. Reports include commit/provider/model metadata, source hashes, file dispositions, validated findings, rejection counts, successful/failed batch counts and limitations. Full source is not duplicated in this table; snippets/hashes retain provenance.

States are `RUNNING`, `COMPLETED`, `PARTIAL`, `UNAVAILABLE`, `INTERRUPTED`. Completion requires all catalog files acknowledged with no rejected findings. Excluded/deferred/failed/unacknowledged files or rejected findings make a successful request partial. No successful batches, mock mode or no eligible batches produce unavailable. Successful batch count means a schema-valid response, not proof of review quality.

File dispositions: `PENDING` in plans; `DEFERRED`, `EXCLUDED`, `REVIEWED`, `REVIEWED_WITH_REJECTIONS`, `NOT_REVIEWED`, `FAILED` in reports. A batch with rejected findings conservatively marks all acknowledged files as reviewed with rejections.

Reports save at attempt completion. Lost `RUNNING` attempts older than ten minutes become interrupted without fabricated findings; retry is explicit. This is an in-process synchronous flow, not a durable distributed job queue. Capacity is per instance, not a global tenant quota. History lists the latest 50 records; older known IDs remain readable.

Hibernate development profiles create/update the table. For explicitly managed databases, apply:

```sql
CREATE TABLE code_reviews (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    version BIGINT,
    project_id BIGINT NOT NULL,
    snapshot_id VARCHAR(64) NOT NULL,
    status VARCHAR(255) NOT NULL,
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    report_json TEXT NOT NULL
);
CREATE INDEX ix_code_review_project ON code_reviews(project_id, id);
```

## API

All routes are below `/api/projects/{projectId}/code-reviews` and use existing project authorization, including existing reviewer/admin role rules. Creation requires write access; reads require read access.

| Method | Route | Purpose |
|---|---|---|
| GET | `/plan` | Snapshot, provider/model, eligible/excluded files and batch count |
| POST | root | `{ "snapshotId": "64-character SHA-256", "batchOffset": 0 }` |
| GET | root | Latest 50 review summaries |
| GET | `/{id}` | Frozen review |
| GET | `/{id}/download` | JSON attachment with `Cache-Control: no-store` |

Stale snapshots, bad offsets and malformed requests return 400 before provider invocation. Missing/out-of-project IDs return 404 after authorization; unauthenticated/unauthorized callers receive 401/403. Raw provider errors are not stored as findings or returned to the user. Reads never regenerate a report.

## Verification

Tests cover all four languages; security/performance improvements and positive practices; authorization and stale snapshots; frozen downloads after catalog changes; sensitive/oversized file exclusions; catalog hash validation; continuation; mock/provider failures; interrupted attempts; invalid locations, snippets, output shape and enums. A local HTTP fixture exercises the actual provider client's JSON protocol without external model calls.

Browser verification uses a disposable H2 project and a labeled `phase5-ui-fixture` provider: scope loading, explicit review creation, good-practice/security finding rendering, exact snippets, kind/category filters and authenticated saved JSON were checked. [The downloaded sample](verification/phase5-fixture-review.json) is fixture evidence, **not live-model quality evidence**.

```sh
TEST_REAL_CONTAINER=true ./mvnw --batch-mode --no-transfer-progress verify
npm --prefix frontend run lint
npm --prefix frontend run build
python3 -m unittest discover -s worker/polyglot -p 'test_*.py'
python3 -m unittest discover -s evaluation/tests
```

See [verification summary](verification/phase5-summary.json) for final counts and limitations. Phase 6 and live reviewer-quality evaluation are not claimed complete.

# Revised Phase 6 — related context, standards and evaluation

Phase 6 connects snapshot-scoped retrieval to the multilingual test-draft and AI-review workflows. It adds versioned coding guidance, validates cross-file evidence, and supplies reproducible evaluation tools. It does not require another service, vector database, credential or schema migration. Existing provider configuration still applies; mock reviews remain unavailable and mock drafts remain skipped scaffolds.

## Product behavior

Generate a new draft or start an AI code review as before. Expand **Related context and language standards** in the saved result to see the exact excerpts, original file/line ranges, source hashes, retrieval limits and official standards references. Reviews group retrieval by batch. Findings can display applicable standard IDs. The review JSON download includes these traces.

Draft results contain `retrieval: ContextBundle`. Review reports use `testpilot-review-v2`, with `retrieval: [{batchOffset, context}]` and optional `findings[].standardIds`. Old Phase 5 reports deserialize with empty retrieval/standard lists. Reads and downloads use saved context; later intake changes never silently replace it. Authorization, stale-snapshot checks, explicit invocation and immutable history continue to apply.

## Retrieval design and boundaries

`SnapshotContextRetriever` indexes only the catalog already loaded and authorized by the owning service. Each invocation builds an ephemeral lexical index; there is no shared/global cache, filesystem traversal, external fetch or embedding call. The existing legacy Java workflow's scoped hybrid/pgvector RAG remains separate.

- Catalog guard: at most 1,000 files / 12 million source characters, canonical unique paths. Primary files must match the catalog's path and content exactly. Snapshot identity includes project and source hashes in the owning services.
- Supported context: Java, Python, JavaScript and TypeScript. Sensitive/excluded paths, unsupported files and files over 100,000 characters / 5,000 lines are omitted. Review primary-file limits remain 30,000 characters / 2,000 lines; a larger file can supply a bounded context excerpt without being claimed as reviewed.
- Comment/string masking and lexical declaration matching identify type/function/variable names. Query terms are identifiers of three or more characters. Retrieval requires an exact named symbol or module-basename hint; generic vocabulary overlap alone cannot retrieve a file.
- Ranking: symbol match 100, module hint 50, up to 20 shared terms; stable path/line tie breaking. At most 100 declaration chunks per file, 40 lines / 3,000 characters per chunk; final context at most six excerpts / 12,000 source characters. Related excerpts can overlap.
- Primary files are excluded from related snippets because their complete bounded source is already in the prompt. Every excerpt has an exact original line interval, source SHA-256, excerpt SHA-256, score and match reason. The trace records snapshot, query hash, index version, standards version, candidates, supplied characters and limitations.
- JSON-encoded retrieval stays inside the existing untrusted-data prompt boundary. Draft generation uses retrieved excerpts instead of the earlier directory-based context selection; framework detection still examines the catalog manifests.

This is bounded **lexical RAG**, not compiler name resolution or a call graph. Same-name symbols can be unrelated; aliases, short names, dynamic calls, overloads, re-exports and template interpolation can be missed. The model is instructed to inspect definitions before assuming relationships. No claim of exhaustive repository understanding or improved live-model accuracy is made.

## Language standards and evidence validation

`src/main/resources/standards/review-v1.json` is the versioned, offline project baseline `testpilot-language-standards-v1`:

| Language | Baseline rules | Official reference |
|---|---|---|
| Java | Resource closure; SQL parameters | [Resource management](https://docs.oracle.com/javase/tutorial/essential/exceptions/tryResourceClose.html), [prepared statements](https://docs.oracle.com/javase/tutorial/jdbc/basics/prepared.html) |
| Python | Shell invocation; mutable defaults | [Subprocess security](https://docs.python.org/3/library/subprocess.html#security-considerations), [default arguments](https://docs.python.org/3/tutorial/controlflow.html#default-argument-values) |
| JavaScript | Deliberate equality semantics | [Strict equality](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Operators/Strict_equality) |
| TypeScript | Nullability contracts | [strictNullChecks](https://www.typescriptlang.org/tsconfig/strictNullChecks.html) |

These six paraphrased rules are a small baseline, not an exhaustive security standard or a claim that a project's configuration enables a particular rule. Guidance is filtered by primary-file languages and frozen with each result. Model-provided standard IDs must be unique (maximum three), supplied in this context, and match at least one primary evidence language. A model may omit standard IDs when none applies. Validation of an ID does not prove the rule applies semantically.

Review findings must cite at least one acknowledged primary file. Supporting citations may use only exact lines inside a supplied related excerpt. Invented paths/lines, changed text, citations outside the excerpt, unknown/foreign-language standards, context-only findings and attempts to acknowledge a context-only file as fully reviewed are rejected. Existing limits of three spans, 20 lines per span and 30 findings per batch continue to apply. Rejections are counted; malformed batch acknowledgements fail the batch.

**Location validation is not semantic verification.** It prevents fabricated structured file/line/snippet references from being accepted. Free-form explanation/guidance can still be wrong or mention unsupported APIs; humans must review conclusions, severity and proposed fixes. Prompt boundaries reduce instruction confusion but are not a proof that a model ignores all malicious source text.

## Evaluation and evidence

The Phase 6 suite separates three kinds of evidence:

1. **Actual retrieval/validation implementation:** a pinned four-language dataset is executed through production Java classes. It checks expected related-file selection, supplied standards, valid cross-file spans and 20 invalid finding fixtures. Additional tests cover budgets, sensitive paths, comments/strings, snapshot isolation, persistence and old-report compatibility. [Recorded context evidence](verification/phase6-context-evidence.json) is a small deterministic regression, not a live-model benchmark.
2. **Reviewer quality evaluator:** a pinned annotated dataset includes improvement and good-practice references. `reviewer_quality.py` measures grounding independently, and precision, recall, false discoveries, actionability and severity agreement only when exact-report-bound adjudications are supplied. Empty denominators are `null`; missing judgments cannot become a perfect score; duplicate findings cannot inflate recall. The bundled six-candidate fixture intentionally contains one false/hallucinated finding. Its [metric results](verification/phase6-review-quality.json) test the evaluator, not a model.
3. **Test usefulness:** `test_usefulness.py` executes two baseline repetitions and one seeded non-equivalent defect per candidate in the unchanged offline Docker worker. Bundled Java/JUnit, Python/pytest, JS/Jest and TS/Vitest strong/vacuous pairs produce 24 real container runs. Strong tests kill the supplied defect; vacuous ones survive it. Compilation, timeout, dependency, infrastructure or empty-result failures produce unavailable scores, never killed mutants. [Raw results](verification/phase6-usefulness.json) retain tests, coverage, image identity and dataset hash. Two repetitions are only sample consistency; one mutant is not broad mutation coverage. Existing line coverage and seeded-defect detection measure different things.

Exported generated tests can be supplied through a separately pinned `--dataset`; reviewer outputs can be scored with custom reports and independent adjudications. See [evaluation formats and commands](../evaluation/phase6/README.md). No live AI provider was called for the recorded Phase 6 evidence, and no live reviewer precision/recall or generated-test usefulness claim follows from the fixture results.

CI runs evaluation unit tests, the Java context/citation benchmark, annotated reviewer metrics and real container usefulness checks, and uploads their evidence. Existing execution isolation and optional PostgreSQL jobs remain in place. See [verification summary](verification/phase6-summary.json) for local counts and limitations.

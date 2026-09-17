# Revised Phase 2 — multi-language test planning and generation

## Scope

Historical phase boundary: execution described below as future work is now
implemented and locally verified by [revised Phase 3](REVISED_PHASE_3.md). Generation semantics in this document remain applicable.

Implemented: adapter interface/registry, Java/JUnit 5 + Mockito, Python/pytest,
JavaScript and TypeScript/Jest or Vitest, unit/module/integration plans,
deterministic unique names, framework-specific prompts, static generated-test
validation, project-scoped saved drafts, and the project-page planning UI.

Not implemented here: Python/Node execution, syntax/compile verification,
automatic dependency installation, coverage, new AI code reviewer, or system/E2E
testing. The existing LangGraph execution workflow remains Java-only. Its Java
generation step now shares the adapter's naming, prompt and validation logic.
Multi-language planning/generation uses the new draft API; it does not enter the
execution graph. This prevents accidental use of the Java runner for Python/JS.

## Use

1. Restart the backend and reload the frontend. The development/H2 Hibernate
   schema-update configuration creates `test_generation_drafts`. Deployments using
   schema validation need a reviewed migration for the new entity/table first.
2. In a project, open **Multi-language test planning and generation** and click
   **Load / refresh plan**. The selected repository catalog is authoritative;
   existing tests/build files are context, not generation targets. Legacy manually
   uploaded Java sources work when no repository is connected.
3. Filter targets and select Unit, Module or Integration. The boundary detector
   uses imports, sibling source files and recognizable dependency/framework signals.
   These are heuristics, not an AST dependency graph. Undetected module/integration
   boundaries are displayed as inapplicable and cannot be generated. Unsupported
   languages and TypeScript declaration-only files are listed explicitly.
4. Generate one draft at a time. Review its planned file path, model/provider,
   validation checks and complete code. The latest 100 saved drafts are displayed;
   drafts from an older source snapshot are labelled. None is executed or written
   into the repository by this feature.

## API

- `GET /api/projects/{id}/test-plan`: snapshot identifier, commit, plan items,
  unsupported sources and configured provider.
- `POST /api/projects/{id}/test-drafts`: `{ "snapshotId": "...", "planId": "..." }`.
  Server reconstructs the plan; clients cannot supply arbitrary source, framework,
  output path or level. Requires project write access. Stale/unknown/inapplicable
  plans are rejected. Source is checked again after model generation.
- `GET /api/projects/{id}/test-drafts`: latest 100 persisted drafts; requires project
  read access. Read endpoints and generation are cross-user authorization tested.

## Naming and prompts

Names contain a sanitized source stem, test level and a 16-hex-character path hash.
Identical basenames in different directories and different levels produce distinct
outputs. Plans additionally bind the project, source/context snapshot and framework.
Generated identities must match exactly; model-chosen names are rejected.

Java preserves the declared package and uses JUnit level tags. Python uses pytest
level markers (marker registration belongs to the later runner configuration).
JS/TS selects Vitest or Jest from the nearest ancestor package.json dependencies;
absent a recognized declaration, Vitest is explicitly labelled **proposed**, not
installed. Framework configs/scripts and dependencies are never executed.

The first prompt line is a serialized server-owned control record. Source,
analysis and retrieval context are untrusted sections. Mock generation reads only
that control line, fixing the previous failure where `TEST_LEVEL: INTEGRATION`
inside repository source caused every specialist to choose the same class name.
This is not a claim that prompt boundaries prevent every model prompt injection.

Primary source is limited to 100,000 characters (oversized targets fail visibly).
Context is limited to 12 files, 8,000 characters per file and 60,000 total; it may
be partial. The draft records this limitation. Legacy Java calls retain RAG and
analysis context; the new draft flow uses repository context, not a new retrieval
pipeline. It does not infer complete transitive imports or dependencies.

## Validation and honest result states

Common checks cover expected identity, bounded code/metadata, unique metadata test
names, empty/fenced output, common process/dynamic-execution/network constructs,
and obvious placeholders/skipped/vacuous output from real providers. Adapters add
framework structure, assertions and level markers, Java package/class matching,
and selected unit/module infrastructure restrictions.

These are static lexical heuristics, not parsers, compilers, security sandboxes or
proof of behavior. Obfuscated dangerous code and invalid syntax can evade them.
`STRUCTURALLY_VALIDATED` means those checks passed; it never means safe, compiled
or passing. Drafts always record `NOT_EXECUTED`. Invalid responses are rejected
before persistence; no automatic repair loop or retry-driven model spending is
introduced in this phase.

With the default mock provider, results are **MOCK_SCAFFOLD**: intentionally skipped
examples for testing the pipeline, not useful behavioral tests. Configure the
existing real provider for actual generation. No live-provider quality claim is
made by the automated tests. Mock Java generation also uses explicitly disabled
scaffolds rather than an always-passing assertion.

## Verification

Tests exercise deterministic names, all three levels across Java/Python/JS/TS,
nearest-manifest selection, unsupported inputs, prompt-level contamination,
placeholder rejection, malformed/unsafe output, authorization, stale plans,
persistence and separation from executable test records. Frontend lint and build
checks cover the new UI. No system-testing feature was added.

Verified on 2026-09-16: `./mvnw -q test` completed with 86 tests total,
85 passed, zero failures/errors, and one optional integration test skipped.
`npm run lint`, `npm run build`, and `git diff --check` passed. This verification
used deterministic fixtures/mock generation, not a live model or live browser
acceptance run. The existing running backend has not been restarted by this change.

No commits or pushes are performed by the implementation agent.

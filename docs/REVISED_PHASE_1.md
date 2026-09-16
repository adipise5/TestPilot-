# Revised Phase 1: GitHub URL and repository intake

This phase implements intake, not the promised end-to-end multilingual testing or
the new AI code reviewer. Unit, module and integration testing remain the target;
system/browser E2E testing is excluded.

## Usage

1. Restart the backend after updating. The existing development/H2 profiles use
   Hibernate schema update, which adds `repository_ingestions.selection_report`.
   Deployments using schema validation need an equivalent reviewed TEXT-column
   migration and updated enum constraints for SOURCE_CODE / DOCUMENTATION and
   REPOSITORY_DOCUMENTATION before rollout.
2. Open a project, select **Connect repository**, and paste an HTTPS GitHub
   repository URL, with or without `.git`. Leave revision blank for the repository's
   actual default branch. File/tree URLs and credential-bearing URLs are rejected.
3. Existing GitHub MCP token/allowlist or GitHub App installation authorization is
   still required. No tokens belong in the URL, frontend, or committed files.
4. For an existing legacy catalog, click **Refresh branch**. The first refresh
   rescans it with the new policy, replacing the legacy catalog for that SHA; later
   refreshes of the same SHA reuse its completed selection evidence. Historical
   test runs are not rerun. The current schema stores one catalog per repository/SHA,
   not multiple policy-version snapshots.
5. Inspect **Repository file selection**: included/excluded paths, languages,
   reasons, and read-only build/test configuration hints. Filter by path or reason.

## Selection policy

- Recognizes common Java, Python, JS/TS, Go, Rust, C/C++, C#, Kotlin, Ruby, PHP,
  Swift, Scala, Dart, Elixir, R, Lua, shell, SQL, and web source extensions.
  Unknown extensions are explicitly excluded, not claimed as supported.
- Existing tests and benchmark/fixture source are context, not production targets.
- Maven/Gradle, Node, Python and other recognized manifests are read-only context.
  Test command hints are heuristics, never validated execution results.
- Filters common dependency/build/cache/generated directories, mutation outputs,
  minified bundles, lock files, symlinks, submodules and known sensitive paths.
  Rejects non-UTF-8/binary content, recognized generated headers and credential patterns.
  These heuristics are not an exhaustive secret scanner or semantic importance detector.
- Excluded content is not stored in selection evidence. Path-excluded files are
  not fetched; content-rejected files must be fetched to classify them.
- Bounds: 50,000 tree entries, 1,000 candidate files, 512 KiB/file, 10 MiB selected
  content. Oversized/truncated inventories fail visibly, not silently partially succeed.
- All reads remain pinned to the resolved commit and project authorization applies
  to `/api/repositories/{id}/selection` as well as catalog access.

## Compatibility and remaining work

Legacy API owner/name requests still work; new clients send `repositoryUrl`.
The existing runner receives only Java `src/main/java` production files.
Other languages are cataloged and mapped into existing RAG document types, not
executed. The new reviewer, robust test generation, isolated multilingual runners,
and the previously observed `test_reviewer` workflow failure are not completed by
this phase. No repository code or installation scripts run during intake.

## Checks

URL validation tests, multilingual classification/exclusion tests, manifest-hint
tests, and authenticated intake/selection/idempotency tests cover this phase.
Frontend checks: `npm run lint` and `npm run build`.

Verified on 2026-09-16: backend `./mvnw -q test` completed with 79 tests,
78 passed and one optional integration test skipped; frontend lint and production
build passed. The backend suite needed local mock-server socket access outside the
restricted sandbox. This is automated verification, not a live GitHub/browser
acceptance run. No commit or push was performed.

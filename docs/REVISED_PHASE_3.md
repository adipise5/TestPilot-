# Revised Phase 3 — secure test execution

Status: **complete and locally verified**, ready for owner review/manual push. See [analysis](REVISED_PROJECT_ANALYSIS.md).
This phase does not implement the revised Phase 4 report or Phase 5 reviewer.

## Setup and use

```bash
docker build -t testpilot-polyglot:local -f worker/polyglot/Dockerfile .
python3 worker/polyglot/smoke.py --image testpilot-polyglot:local
export TEST_SECURE_WORKER_IMAGE=testpilot-polyglot:local
./mvnw spring-boot:run -Dspring-boot.run.profiles=h2
```

H2 is ephemeral (`create-drop`), and **also requires Docker** for execution.
The application never automatically builds or pulls an image. Missing Docker,
an unavailable image or an invalid worker response fails visibly; there is no
host compiler/test-runner fallback. The older `worker/Dockerfile` is a historical
artifact, not the image used by the application.

On the project page, load the plan, generate a real-provider draft, review its
source and click **Execute / retry in container**. Use **Load last execution** to
retrieve persisted evidence after reload or a disconnected HTTP request. Stale
drafts must be regenerated. Mock scaffolds cannot be executed through this API.

Dependencies are installed only when an operator builds the trusted image from
its reviewed lockfiles/bootstrap project. Repository manifests are never used
to download packages or run install scripts on the application host. Maven's
repository POM/plugins can execute *inside* the offline container, subject to
available image dependencies. Python and JS use image-owned runner configs. Vitest receives a copy of its trusted
config in `/work`, since Vite bundles that file at startup; the image stays read-only.

## Language support

| Language | Compile / syntax check | Test runner | Limits |
| --- | --- | --- | --- |
| Java 17 | Offline `mvn test-compile` | Offline `mvn -Dtest=<qualified classes> surefire:test`; Surefire XML | Root single-module Maven, standard `src/main/java` and `src/test/java`; default JUnit 5/Mockito POM only when no POM exists; dependencies/plugins must be baked in |
| Python | Isolated `python -I -m compileall` | `python -I -m pytest`, explicit draft paths, JUnit XML | Root/src imports, pytest and stdlib; no repository conftest, automatic plugins or package installation |
| JavaScript | Babel syntax check of generated files | Explicit Jest or Vitest CLI, fixed config, selected paths | Node environment; no browser/jsdom, project configs or dependency installation; imported-source syntax failures classified from runner evidence |
| TypeScript | `tsc --noEmit`, explicit generated files and imports | Jest/Babel or Vitest, selected paths | Fixed ES2022/ESNext/bundler compiler options; no project tsconfig/plugins; application type packages must be available |

The image pins its Node/Maven base-image digests and pytest, Jest, Vitest,
TypeScript, Babel, JUnit and Mockito tooling. Its build context excludes application
files, Git metadata, secrets and host build outputs.
It is not a universal dependency environment and does not promise compatibility
with every project version, JSX framework, alias, native extension or build setup.
Gradle, nested Java source layouts, multi-module Maven and languages without an
adapter return `UNSUPPORTED`. Recognized projects with absent packages return
`DEPENDENCY_FAILURE` where diagnostics identify missing dependencies. Unrecognized
runner/setup failures remain infrastructure/report failures, never success.
`Vitest (proposed)` uses the image's installed Vitest and remains a proposed
framework choice, not evidence that the repository declared it.

## Boundary and resource policy

Both legacy Java runs and new drafts use `SecureContainerExecutor`:

- Non-root UID/GID 10001, read-only root, all capabilities dropped,
  `no-new-privileges`, Docker init and `--network none`.
- Memory/swap ceiling 768 MiB, one CPU, 128 PIDs, 512 file descriptors and 16 MiB
  maximum individual output file size; Docker logging disabled.
- Writable storage is bounded tmpfs: 256 MiB `/work`, 64 MiB `/tmp`; no writable
  host mount. `/tmp` is also `noexec`.
- A private host directory contains one JSON snapshot mounted read-only at
  `/input`. No source-controlled path becomes a host materialization path. The
  worker alone materializes source files inside its tmpfs.
- No Docker socket, host home, credentials or inherited application environment
  is passed into test processes. Only fixed tool PATH, HOME and runner settings
  are supplied.
- The worker has a shared 55-second compilation/test deadline; the application
  has a 70-second container deadline plus up to five seconds for cleanup.
- Two concurrent executions per application instance; excess requests return
  `CAPACITY_EXCEEDED`. This is not a cluster-wide quota or a persistent queue.
- Container removal is attempted in `finally`; input directories are removed.
  If the daemon is unreachable, operator cleanup by the
  `testpilot.secure-worker=true` label may be necessary.

Repository paths, file counts, contents and total request size are bounded;
collisions, traversal, excluded paths and a missing selected source are rejected.
Catalog SHA-256 values are checked before draft planning and again when building
execution input. Draft identity and static validation are rechecked before launch.

## API and persistence

`GET` and `POST /api/projects/{projectId}/test-drafts/{draftId}/execution` return
`draftId`, `status`, `startedAt`, and a nullable `result` containing `outcome`,
`exitCode`, bounded `output` and `{name,status,message,seconds}` test cases.

GET requires project read authorization; POST requires write authorization and a
current, real-provider, validated draft. Both are scoped to the project. The POST
is synchronous. A unique row per draft and optimistic locking prevent concurrent
restarts from launching duplicate attempts; a concurrent request sees `RUNNING`.
A subsequent explicit retry replaces the previous result (no attempt history).
Recovery requests cleanup for rows still running after 100 seconds and records an
interrupted-worker failure. It does not automatically execute a draft again.

The older Java workflow retains its separate leased jobs, cancellation and retry
semantics. Input rejection and capacity limits now retain their typed outcomes
instead of being converted to generic infrastructure failures.

## Honest results

- `SUCCESS` requires exit code zero, at least one passing case and no reported
  failed/error case. Empty or all-skipped evidence is `NO_TESTS`.
- `TEST_FAILURE` requires failing/error case evidence. Syntax/compiler,
  dependency, timeout, unsupported, invalid-report and infrastructure outcomes
  remain distinct. `COMPLETED` means the attempt finished, not that tests passed.
- Report reads are limited to regular files; symlinks, oversized documents,
  DTD/entities, invalid encodings, unknown statuses, invalid durations and malformed
  fields are rejected. JUnit and Jest results share one server-validated schema.
- Report files are capped at 1 MB combined and 1,000 cases; diagnostics and output
  are bounded. Worker stdout is capped independently by the host process wrapper.
- Test reports are **untrusted execution evidence**, not attestation. Repository
  code can deliberately falsify assertions or write reports inside its container.
  A passing result alone does not prove test usefulness or safety for delivery.
- Coverage and mutation evidence remain unavailable in this worker. No inferred
  percentage is emitted. Phase 4 must add measured report evidence explicitly.

## Verification

Local verification on 2026-09-17, Docker 29.8.1 / Linux ARM64 / cgroup v2:

- **25/25 real-container acceptance cases passed** against the final image:
  Java, Python, Jest/Vitest for JS and TS, assertions, syntax, missing dependencies,
  skipped/empty tests, unsupported inputs, timeout and isolation/resource limits.
- **98 backend tests, zero failures/errors, one optional pgvector test skipped.**
  `TEST_REAL_CONTAINER=true` enabled both real-executor tests in this full run:
  successful compilation/execution/parsing and cancellation/container cleanup.
- Worker: nine parser/command/materialization tests passed.
- API integration covers execution persistence/retry, ownership, missing drafts,
  stale snapshots, mock refusal, fresh validation and Docker failure. These API
  tests stub Docker responses; the separate real tests exercise the actual boundary.
- Earlier unchanged quality gates also passed: four orchestrator tests, five
  evaluation metric tests, pinned Java benchmark thresholds, frontend lint/build
  and production npm audit (zero vulnerabilities).
- No execution containers remained after verification. Python compilation and
  `git diff --check` passed. CI builds the same image and reruns container/API
  executor checks, uploading JSON evidence; this local change has not been pushed.

[Machine-readable summary](verification/phase3-summary.json) binds verification to
source hashes and the tested image ID. [All container case results](verification/phase3-container-evidence.json)
include actual outcomes, diagnostics and durations. Fixture results establish the
execution pipeline, not live-model test usefulness.

The live run found and fixed Vitest's attempt to bundle its config onto the
read-only image. JVM temporary storage also had to be configured at JVM startup
for the narrowly mounted macOS verification VM; a late Surefire system property
does not change Java's cached default temporary path.

## Local Docker environment prepared in this workspace

Docker CLI/Buildx, Colima and Lima were installed. Colima's GitHub image download
failed, so verification used Lima's Ubuntu 24.04 Docker VM named `testpilot`.
It has two CPUs, 4 GiB memory, a 20 GiB virtual disk, and **only** this repository's
`target/docker-input` directory shared read-only. Docker's global context and
shell startup files were not changed. The VM remains running for local use.

From this repository's root, use the prepared VM as follows:

```bash
# Run this if the VM has been stopped:
limactl start testpilot
export DOCKER_HOST="unix://$HOME/.lima/testpilot/sock/docker.sock"
export TMPDIR="$PWD/target/docker-input"
export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$PWD/target/docker-input"
export TEST_SECURE_WORKER_IMAGE=testpilot-polyglot:local
# If the worker needs rebuilding, Homebrew's Buildx can be invoked directly:
/opt/homebrew/lib/docker/cli-plugins/docker-buildx build --load \
  -t "$TEST_SECURE_WORKER_IMAGE" -f worker/polyglot/Dockerfile .
python3 worker/polyglot/smoke.py --image "$TEST_SECURE_WORKER_IMAGE"
TEST_REAL_CONTAINER=true ./mvnw --batch-mode test
# Or launch the backend with the same environment:
./mvnw spring-boot:run -Dspring-boot.run.profiles=h2
# Stop only the dedicated VM when finished:
limactl stop testpilot
```

Docker Desktop/native Linux users can use the normal setup above with a shared
system temporary directory instead. If the repository moves, update the VM's
read-only mount. This VM setup is local development infrastructure, not a
production deployment architecture.

No commits or pushes are performed by the implementation agent. Revised
Phases 4–6 remain separate changes after owner review of Phase 3.

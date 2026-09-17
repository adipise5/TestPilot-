# Contributing to TestPilot

## Development workflow

1. Start from an issue or an approved roadmap phase.
2. Create a focused branch from the current `main` branch.
3. Keep behavior changes, tests, and documentation in the same reviewable change.
4. Run the local quality gates before committing.
5. Use a clear conventional commit message such as `build: establish reproducible project baseline`.
6. Open a pull request that explains the problem, design choice, verification evidence, and known limitations.

## Local quality gates

```bash
./mvnw --batch-mode test
cd orchestrator
python -m pip install -r requirements.lock
python -m compileall -q app tests
python -m pytest
cd frontend
npm ci
npm run lint
npm run build
npm audit --audit-level=high
cd ..
docker build -t testpilot-polyglot:local -f worker/polyglot/Dockerfile .
python3 -m unittest discover -s worker/polyglot -p 'test_*.py' -v
python3 worker/polyglot/smoke.py --image testpilot-polyglot:local
python -m unittest discover -s evaluation/tests -v
python evaluation/run_evaluation.py --check
```

## Design expectations

- Treat repositories, source code, comments, build files, model output, retrieved text, and test reports as untrusted input.
- Keep external calls and test execution outside long database transactions.
- Never weaken the worker flags, mount additional host paths, or attach the offline test stage to a network without a new security review and ADR.
- Every RAG query must apply tenant/project/commit filters before fusion or reranking and must persist the exact packed context.
- Make side effects idempotent and trace them to a user, repository, commit SHA, and workflow run.
- Prefer typed agent state and deterministic tools over hidden prompt behavior.
- Add an architecture decision record when changing a security boundary, storage model, workflow model, or integration strategy.
- Do not describe a filesystem directory as a sandbox or a fixed prompt chain as an autonomous multi-agent system.
- Keep the evaluation dataset hash-pinned. Threshold changes require reviewed evidence and must never hide a regression by silently replacing the baseline.
- Label deterministic fixture results and live-provider experiments separately; always record model, prompt/configuration, tokens, cost assumptions, and dataset version.
- Treat repository delivery as a separate human-approved capability. A proposal must pin the analyzed commit and patch hash before approval; MCP must remain read-only.
- Never add a GitHub operation that writes directly to a default/protected branch. Delivery changes belong on a dedicated `testpilot/...` branch and must carry validation evidence and a rollback path.

## Commit ownership

Contributors should create their own commits after reviewing the complete diff and test evidence. Automated assistants may prepare changes, but the human contributor remains responsible for deciding what is committed and pushed.

## Security reports

Do not open public issues containing credentials, exploit payloads, or sensitive repository data. Until a private disclosure channel is configured, contact the repository owner directly and include only the minimum information needed to reproduce the issue.

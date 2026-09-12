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
```

## Design expectations

- Treat repositories, source code, comments, build files, model output, retrieved text, and test reports as untrusted input.
- Keep external calls and test execution outside long database transactions.
- Make side effects idempotent and trace them to a user, repository, commit SHA, and workflow run.
- Prefer typed agent state and deterministic tools over hidden prompt behavior.
- Add an architecture decision record when changing a security boundary, storage model, workflow model, or integration strategy.
- Do not describe a filesystem directory as a sandbox or a fixed prompt chain as an autonomous multi-agent system.

## Commit ownership

Contributors should create their own commits after reviewing the complete diff and test evidence. Automated assistants may prepare changes, but the human contributor remains responsible for deciding what is committed and pushed.

## Security reports

Do not open public issues containing credentials, exploit payloads, or sensitive repository data. Until a private disclosure channel is configured, contact the repository owner directly and include only the minimum information needed to reproduce the issue.

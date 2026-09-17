# TestPilot execution worker

The application uses **`worker/polyglot/Dockerfile`** for Java, Python, JavaScript
and TypeScript in every profile, including H2. There is no host fallback and no
runtime dependency-download stage.

```bash
docker build -t testpilot-polyglot:local -f worker/polyglot/Dockerfile .
python3 -m unittest discover -s worker/polyglot -p 'test_*.py' -v
python3 worker/polyglot/smoke.py --image testpilot-polyglot:local
```

Set `TEST_SECURE_WORKER_IMAGE` to use another reviewed prebuilt image. It must
honor the same runner protocol. The application uses `--pull=never`.

The image preinstalls locked Node tools, pytest and a warmed Maven JUnit/Mockito
cache. Missing project dependencies fail offline. No repository install scripts
are used to prepare this image. Test processes run as UID/GID 10001, without
network/capabilities, with a read-only root, bounded tmpfs and CPU/memory/PID/time
limits. Only the snapshot JSON is mounted from the host, read-only.

See [Phase 3](../docs/REVISED_PHASE_3.md) for supported layouts, exact limits,
outcome semantics and verification status. `Dockerfile` and `isolation-probe.sh`
in this directory describe the historical Maven-only image; the current
application does not select it.

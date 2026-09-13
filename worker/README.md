# TestPilot execution worker

This image is the only production path allowed to compile or execute repository code. The Spring service invokes it in two separate stages:

1. dependency resolution with the configured dependency network;
2. offline test execution with `--network none`, a read-only root filesystem, all Linux capabilities dropped, `no-new-privileges`, and PID/CPU/memory limits.

The worker runs as UID/GID `10001`, receives only a per-run workspace and dependency cache, and is removed after each command. `TEST_EXECUTION_BACKEND=local` is intended only for the H2 test profile and trusted developer verification.

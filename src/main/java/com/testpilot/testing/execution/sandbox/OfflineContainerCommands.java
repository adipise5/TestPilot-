package com.testpilot.testing.execution.sandbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.Path;
import java.util.List;

@Component
public class OfflineContainerCommands {
    private final String image;
    public OfflineContainerCommands(@Value("${testpilot.execution.secure-worker-image:testpilot-polyglot:local}") String image) {
        if (image == null || !image.matches("[A-Za-z0-9][A-Za-z0-9._/:@-]{0,250}")) throw new IllegalArgumentException("Invalid worker image reference");
        this.image = image;
    }
    public List<String> run(String name, Path input) {
        if (!name.matches("testpilot-secure-[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid container name");
        String mount = input.toAbsolutePath().toString();
        if (mount.contains(",") || mount.contains("\n")) throw new IllegalArgumentException("Unsupported Docker bind path");
        return List.of("docker", "run", "--rm", "--pull=never", "--name", name,
                "--label", "testpilot.secure-worker=true", "--network", "none", "--read-only",
                "--user", "10001:10001", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--memory", "768m", "--memory-swap", "768m", "--cpus", "1.0", "--pids-limit", "128",
                "--ulimit", "nofile=512:512", "--ulimit", "fsize=16777216:16777216",
                "--log-driver", "none", "--init", "--workdir", "/work",
                "--tmpfs", "/work:rw,nosuid,nodev,size=268435456,uid=10001,gid=10001,mode=0700",
                "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=67108864,uid=10001,gid=10001,mode=0700",
                "--mount", "type=bind,src=" + mount + ",dst=/input,readonly",
                "--entrypoint", "/opt/python/bin/python", image, "-I", "/opt/testpilot/runner.py");
    }
    public List<String> remove(String name) {
        if (!name.matches("testpilot-secure-[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid container name");
        return List.of("docker", "rm", "--force", name);
    }
}

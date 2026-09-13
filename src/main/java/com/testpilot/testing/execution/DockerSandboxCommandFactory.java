package com.testpilot.testing.execution;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class DockerSandboxCommandFactory {

    private final String image;
    private final String dependencyNetwork;
    private final String memoryLimit;
    private final String cpuLimit;
    private final int pidLimit;

    public DockerSandboxCommandFactory(
            @Value("${testpilot.execution.worker-image:testpilot-worker:local}") String image,
            @Value("${testpilot.execution.dependency-network:bridge}") String dependencyNetwork,
            @Value("${testpilot.execution.memory-limit:1g}") String memoryLimit,
            @Value("${testpilot.execution.cpu-limit:1.0}") String cpuLimit,
            @Value("${testpilot.execution.pid-limit:128}") int pidLimit) {
        this.image = image;
        this.dependencyNetwork = dependencyNetwork;
        this.memoryLimit = memoryLimit;
        this.cpuLimit = cpuLimit;
        this.pidLimit = pidLimit;
    }

    public List<String> dependencyResolution(Path workspace, Path dependencyCache) {
        List<String> command = common(workspace, dependencyCache, true, false);
        command.add("--network");
        command.add(dependencyNetwork);
        command.add(image);
        command.add("mvn");
        command.add("--batch-mode");
        command.add("--no-transfer-progress");
        command.add("dependency:go-offline");
        return List.copyOf(command);
    }

    public List<String> testExecution(Path workspace, Path dependencyCache) {
        return testExecution(workspace, dependencyCache, false);
    }

    public List<String> testExecution(Path workspace, Path dependencyCache, boolean mutationEnabled) {
        List<String> command = common(workspace, dependencyCache, false, true);
        command.add("--network");
        command.add("none");
        command.add("--read-only");
        command.add("--tmpfs");
        command.add("/tmp:rw,noexec,nosuid,nodev,size=64m");
        command.add(image);
        command.add("mvn");
        command.add("--offline");
        command.add("--batch-mode");
        command.add("--no-transfer-progress");
        command.add("clean");
        command.add("test");
        if (mutationEnabled) command.add("org.pitest:pitest-maven:mutationCoverage");
        return List.copyOf(command);
    }

    private List<String> common(
            Path workspace,
            Path dependencyCache,
            boolean readOnlyWorkspace,
            boolean readOnlyDependencies) {
        List<String> command = new ArrayList<>(List.of(
                "docker", "run", "--rm",
                "--user", "10001:10001",
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--pids-limit", Integer.toString(pidLimit),
                "--memory", memoryLimit,
                "--cpus", cpuLimit,
                "--ulimit", "nofile=1024:1024",
                "--ulimit", "fsize=67108864:67108864",
                "--workdir", "/workspace",
                "--mount", "type=bind,src=" + workspace.toAbsolutePath() + ",dst=/workspace"
                        + (readOnlyWorkspace ? ",readonly" : ""),
                "--mount", "type=bind,src=" + dependencyCache.toAbsolutePath()
                        + ",dst=/home/testpilot/.m2/repository"
                        + (readOnlyDependencies ? ",readonly" : "")));
        return command;
    }
}

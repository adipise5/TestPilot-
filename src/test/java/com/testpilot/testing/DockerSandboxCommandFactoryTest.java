package com.testpilot.testing;

import com.testpilot.testing.execution.DockerSandboxCommandFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DockerSandboxCommandFactoryTest {

    private final DockerSandboxCommandFactory commands = new DockerSandboxCommandFactory(
            "testpilot-worker:test", "dependency-egress", "768m", "0.75", 64);

    @Test
    void separatesDependencyNetworkFromHardenedOfflineExecution() {
        List<String> dependency = commands.dependencyResolution(Path.of("/tmp/work"), Path.of("/tmp/cache"));
        List<String> execution = commands.testExecution(Path.of("/tmp/work"), Path.of("/tmp/cache"));

        assertOption(dependency, "--network", "dependency-egress");
        assertFalse(dependency.contains("none"));
        assertTrue(dependency.stream().anyMatch(value -> value.contains("dst=/workspace,readonly")));

        assertOption(execution, "--network", "none");
        assertOption(execution, "--user", "10001:10001");
        assertOption(execution, "--cap-drop", "ALL");
        assertOption(execution, "--security-opt", "no-new-privileges");
        assertOption(execution, "--pids-limit", "64");
        assertOption(execution, "--memory", "768m");
        assertOption(execution, "--cpus", "0.75");
        assertTrue(execution.contains("nofile=1024:1024"));
        assertTrue(execution.contains("fsize=67108864:67108864"));
        assertTrue(execution.contains("--read-only"));
        assertTrue(execution.contains("--offline"));
        assertTrue(execution.stream().anyMatch(value -> value.contains("readonly")));
        assertTrue(commands.testExecution(Path.of("/tmp/work"), Path.of("/tmp/cache"), true)
                .contains("org.pitest:pitest-maven:mutationCoverage"));
    }

    private void assertOption(List<String> command, String option, String value) {
        int index = command.indexOf(option);
        assertTrue(index >= 0, "missing option " + option);
        assertEquals(value, command.get(index + 1));
    }
}

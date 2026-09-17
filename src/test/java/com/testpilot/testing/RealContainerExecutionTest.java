package com.testpilot.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.testing.execution.sandbox.*;
import com.testpilot.testing.generation.SourceInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in acceptance of the real application executor; no process mocks. */
@EnabledIfEnvironmentVariable(named = "TEST_REAL_CONTAINER", matches = "true")
class RealContainerExecutionTest {
    private final SecureContainerExecutor executor = new SecureContainerExecutor(
            new OfflineContainerCommands(System.getenv().getOrDefault("TEST_SECURE_WORKER_IMAGE", "testpilot-polyglot:local")),
            new ContainerProcess(), new ObjectMapper());

    private SandboxRequest python(String test) {
        return new SandboxRequest("Python", "pytest", "app.py",
                List.of(new SandboxRequest.TestFile("tests/test_app.py", test)),
                List.of(new SourceInput("app.py", "def add(a, b): return a + b\n")));
    }

    @Test void applicationExecutorCompilesRunsParsesAndRemovesRealContainer() throws Exception {
        String name = "testpilot-secure-" + UUID.randomUUID();
        var result = executor.execute(python("from app import add\ndef test_add(): assert add(2, 3) == 5\n"),
                name, () -> false, () -> {});
        assertEquals("SUCCESS", result.outcome(), result.output());
        assertEquals(0, result.exitCode());
        assertEquals("PASSED", result.tests().get(0).status());
        assertEquals("MEASURED", result.coverage().status(), result.coverage().note());
        assertEquals("app.py", result.coverage().sourcePath());
        assertEquals(100.0, result.coverage().percent());
        assertContainerRemoved(name);
    }

    @Test void cancellationRemovesTheContainerRatherThanOnlyTheDockerClient() throws Exception {
        String name = "testpilot-secure-" + UUID.randomUUID();
        AtomicBoolean cancelled = new AtomicBoolean();
        var result = executor.execute(python("def test_forever():\n    while True: pass\n"), name,
                cancelled::get, () -> cancelled.set(true));
        assertEquals("CANCELLED", result.outcome(), result.output());
        assertContainerRemoved(name);
    }

    private void assertContainerRemoved(String name) throws Exception {
        Process inspect = new ProcessBuilder("docker", "container", "inspect", name).redirectErrorStream(true).start();
        assertTrue(inspect.waitFor(5, TimeUnit.SECONDS), "Docker inspect did not finish");
        assertNotEquals(0, inspect.exitValue(), "Execution container survived cleanup");
    }
}

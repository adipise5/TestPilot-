package com.testpilot.testing;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.testing.execution.sandbox.*;
import com.testpilot.testing.generation.SourceInput;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SecureContainerExecutorTest {
    private final OfflineContainerCommands commands = new OfflineContainerCommands("testpilot-polyglot:test");
    private final ContainerProcess process = mock(ContainerProcess.class);
    private final SecureContainerExecutor executor = new SecureContainerExecutor(commands, process, new ObjectMapper());
    private final String name = "testpilot-secure-00000000-0000-0000-0000-000000000000";
    private SandboxRequest request(String language) {
        return new SandboxRequest(language, "pytest", "app.py", List.of(new SandboxRequest.TestFile("tests/test_app.py", "def test_app(): assert 1 == 1")),
                List.of(new SourceInput("app.py", "value = 1")));
    }
    @Test void commandIsOfflineReadOnlyResourceLimitedAndHasNoHostWriteMount() {
        var command = commands.run(name, Path.of("/tmp/private/input"));
        for (var option : List.of("--read-only", "--pull=never", "--init")) assertTrue(command.contains(option));
        for (var option : List.of(List.of("--network", "none"), List.of("--user", "10001:10001"),
                List.of("--cap-drop", "ALL"), List.of("--security-opt", "no-new-privileges"), List.of("--memory", "768m"),
                List.of("--memory-swap", "768m"), List.of("--cpus", "1.0"), List.of("--pids-limit", "128"))) {
            assertEquals(option.get(1), command.get(command.indexOf(option.get(0)) + 1));
        }
        assertTrue(command.get(command.indexOf("--mount") + 1).endsWith(",readonly"));
        assertFalse(command.stream().anyMatch(s -> s.contains("docker.sock") || s.equals("bridge") || s.equals("--privileged")));
        assertThrows(IllegalArgumentException.class, () -> commands.remove("arbitrary-container"));
    }
    @Test void unsupportedLanguageAndUnsafePathsNeverLaunchProcesses() {
        assertEquals("UNSUPPORTED", executor.execute(request("Go"), name, () -> false, () -> {}).outcome());
        var unsafe = new SandboxRequest("Python", "pytest", "app.py", request("Python").tests(), List.of(new SourceInput("../escape.py", "")));
        assertEquals("INPUT_REJECTED", executor.execute(unsafe, name, () -> false, () -> {}).outcome());
        var collision = new SandboxRequest("Python", "pytest", "app.py", List.of(new SandboxRequest.TestFile("app.py", "")), request("Python").files());
        assertEquals("INPUT_REJECTED", executor.execute(collision, name, () -> false, () -> {}).outcome());
        verifyNoInteractions(process);
    }
    @Test void noDockerAndTimeoutNeverFallBackToHostAndAlwaysRequestCleanup() throws Exception {
        when(process.run(anyList(), any(), any(), any())).thenReturn(new ContainerProcess.Result(125, false, false, "image unavailable"));
        assertEquals("INFRASTRUCTURE_FAILURE", executor.execute(request("Python"), name, () -> false, () -> {}).outcome());
        verify(process).run(eq(commands.remove(name)), any(), any(), any());
        reset(process);
        when(process.run(anyList(), any(), any(), any())).thenReturn(new ContainerProcess.Result(null, true, false, ""));
        assertEquals("TIMEOUT", executor.execute(request("Python"), name, () -> false, () -> {}).outcome());
        verify(process).run(eq(commands.remove(name)), any(), any(), any());
    }
    @Test void emptySkippedAndContradictorySuccessIsNeverGreen() {
        assertEquals("NO_TESTS", executor.validateResult(new SandboxResult("SUCCESS", 0, "", List.of())).outcome());
        var skipped = new SandboxResult.CaseResult("skip", "SKIPPED", "", 0);
        assertEquals("NO_TESTS", executor.validateResult(new SandboxResult("SUCCESS", 0, "", List.of(skipped))).outcome());
        var failure = new SandboxResult.CaseResult("failure", "FAILED", "", 0);
        assertEquals("INVALID_REPORT", executor.validateResult(new SandboxResult("SUCCESS", 0, "", List.of(failure))).outcome());
        assertEquals("INVALID_REPORT", executor.validateResult(new SandboxResult("SUCCESS", 1, "", List.of())).outcome());
        var pass = new SandboxResult.CaseResult("pass", "PASSED", "", 0.1);
        assertEquals("SUCCESS", executor.validateResult(new SandboxResult("SUCCESS", 0, "", List.of(pass))).outcome());
    }
    @Test void rejectsMalformedReportFieldsWithoutThrowing() {
        assertEquals("INVALID_REPORT", executor.validateResult(new SandboxResult(null, 0, "", List.of())).outcome());
        for (var test : List.of(new SandboxResult.CaseResult("test", null, "", 0),
                new SandboxResult.CaseResult("", "PASSED", "", 0),
                new SandboxResult.CaseResult("test", "PASSED", "", Double.NaN))) {
            assertEquals("INVALID_REPORT", executor.validateResult(new SandboxResult("SUCCESS", 0, "", List.of(test))).outcome());
        }
        assertEquals("INVALID_REPORT", executor.validateResult(new SandboxResult("TEST_FAILURE", 1, "", List.of())).outcome());
    }
    @Test void cancellationAndMissingSourceDoNotLaunchDocker() {
        assertEquals("CANCELLED", executor.execute(request("Python"), name, () -> true, () -> {}).outcome());
        var missing = new SandboxRequest("Python", "pytest", "missing.py", request("Python").tests(), request("Python").files());
        assertEquals("INPUT_REJECTED", executor.execute(missing, name, () -> false, () -> {}).outcome());
        verifyNoInteractions(process);
    }

    @Test void rejectsMalformedAndTrailingWorkerProtocolData() throws Exception {
        for (String output : List.of("not json", "{} {}", "{\"outcome\":\"SUCCESS\",\"exitCode\":0,\"output\":\"\",\"tests\":[]} {}")) {
            when(process.run(anyList(), any(), any(), any())).thenReturn(new ContainerProcess.Result(0, false, false, output));
            assertEquals("INVALID_REPORT", executor.execute(request("Python"), name, () -> false, () -> {}).outcome());
        }
    }

}

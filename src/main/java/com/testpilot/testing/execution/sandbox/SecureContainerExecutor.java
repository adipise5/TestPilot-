package com.testpilot.testing.execution.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.common.validation.RepositoryPathPolicy;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;

@Service
public class SecureContainerExecutor {
    private final OfflineContainerCommands commands;
    private final ContainerProcess processes;
    private final ObjectMapper mapper;
    private final Semaphore capacity = new Semaphore(2);
    public SecureContainerExecutor(OfflineContainerCommands commands, ContainerProcess processes, ObjectMapper mapper) {
        this.commands = commands; this.processes = processes; this.mapper = mapper;
    }

    public SandboxResult execute(SandboxRequest request, String containerName, BooleanSupplier cancelled, Runnable heartbeat) {
        String unsupported = unsupported(request);
        if (unsupported != null) return SandboxResult.failure("UNSUPPORTED", unsupported);
        String invalid = invalidInput(request);
        if (invalid != null) return SandboxResult.failure("INPUT_REJECTED", invalid);
        if (cancelled.getAsBoolean()) return SandboxResult.failure("CANCELLED", "Execution cancelled before launch");
        if (!capacity.tryAcquire()) return SandboxResult.failure("CAPACITY_EXCEEDED", "Two sandbox executions are already running; retry later");
        Path workspace = null;
        try {
            // Parent is private to this process; no repository filename is ever a host path.
            workspace = Files.createTempDirectory("testpilot-sandbox-");
            Files.setPosixFilePermissions(workspace, PosixFilePermissions.fromString("rwx------"));
            Path input = Files.createDirectory(workspace.resolve("input"));
            Files.setPosixFilePermissions(input, PosixFilePermissions.fromString("rwxr-xr-x"));
            String json = mapper.writeValueAsString(request);
            if (json.getBytes(StandardCharsets.UTF_8).length > 12 * 1024 * 1024) return SandboxResult.failure("INPUT_REJECTED", "Snapshot exceeds execution byte limit");
            Files.writeString(input.resolve("request.json"), json, StandardOpenOption.CREATE_NEW);
            Files.setPosixFilePermissions(input.resolve("request.json"), PosixFilePermissions.fromString("r--r--r--"));
            var process = processes.run(commands.run(containerName, input), Duration.ofSeconds(70), cancelled, heartbeat);
            if (process.cancelled()) return SandboxResult.failure("CANCELLED", "Execution cancelled; container removal requested");
            if (process.timedOut()) return SandboxResult.failure("TIMEOUT", "Container exceeded the 70-second host deadline");
            if (process.exitCode() == null || process.exitCode() != 0) return SandboxResult.failure("INFRASTRUCTURE_FAILURE",
                    "Worker/image/Docker failed (no host fallback). " + bounded(process.output(), 4096));
            try {
                SandboxResult result = mapper.readerFor(SandboxResult.class)
                        .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readValue(process.output());
                SandboxResult validated = validateResult(result);
                CoverageEvidence coverage = Set.of("SUCCESS", "TEST_FAILURE").contains(validated.outcome())
                        ? CoverageEvidence.validate(validated.coverage(), request)
                        : CoverageEvidence.unavailable("No completed test execution to measure");
                return new SandboxResult(validated.outcome(), validated.exitCode(), validated.output(), validated.tests(), coverage);
            } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
                return SandboxResult.failure("INVALID_REPORT", "Worker did not return one valid execution result");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return SandboxResult.failure("CANCELLED", "Execution interrupted");
        } catch (Exception ex) {
            return SandboxResult.failure("INFRASTRUCTURE_FAILURE", "Could not run or read the offline worker. Check Docker, the prebuilt image and worker protocol; no host fallback was used.");
        } finally {
            cleanup(containerName);
            if (workspace != null) {
                try { Files.deleteIfExists(workspace.resolve("input/request.json")); Files.deleteIfExists(workspace.resolve("input")); Files.deleteIfExists(workspace); }
                catch (java.io.IOException ignored) { /* Input-only temp directory; no untrusted output mount. */ }
            }
            capacity.release();
        }
    }

    public void cleanup(String name) {
        boolean interrupted = Thread.interrupted();
        try { processes.run(commands.remove(name), Duration.ofSeconds(5), () -> false, () -> {}); }
        catch (Exception ignored) { /* Daemon failure may require operator cleanup by label. */ }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    public String unsupported(SandboxRequest request) {
        if (request == null) return "No execution request supplied";
        if (request.language() == null || !Set.of("Java", "Python", "JavaScript", "TypeScript").contains(request.language())) return "No execution adapter for " + request.language();
        if (request.language().equals("Java") && (!"JUnit 5 / Mockito".equals(request.framework())
                || request.sourcePath() == null || !request.sourcePath().startsWith("src/main/java/"))) return "Java execution currently supports root Maven/standard src/main/java layouts only; Gradle and nested modules are unsupported";
        if (request.language().equals("Python") && !"pytest".equals(request.framework())) return "Only pytest is supported for Python";
        if ((request.language().equals("JavaScript") || request.language().equals("TypeScript"))
                && (request.framework() == null || !Set.of("Jest", "Vitest", "Vitest (proposed)").contains(request.framework()))) return "Only Jest and Vitest are supported for JS/TS";
        if (request.language().equals("Java") && request.files() != null
                && request.files().stream().filter(Objects::nonNull).anyMatch(f -> f.path() != null && (f.path().endsWith("build.gradle") || f.path().endsWith("build.gradle.kts")))
                && request.files().stream().filter(Objects::nonNull).noneMatch(f -> "pom.xml".equals(f.path()))) return "Gradle execution is unsupported; no Maven fallback is attempted";
        return null;
    }

    private String invalidInput(SandboxRequest request) {
        if (request.files() == null || request.tests() == null || request.tests().isEmpty()) return "No source/test snapshot supplied";
        if (request.sourcePath() == null || request.files().stream().filter(Objects::nonNull)
                .noneMatch(f -> request.sourcePath().equals(f.path()))) return "Selected source is absent from the snapshot";
        if (request.files().size() > 1000 || request.tests().size() > 20) return "Execution input exceeds file/test limits";
        var policy = new RepositoryPathPolicy();
        Set<String> paths = new HashSet<>();
        try {
            for (var file : request.files()) {
                String normalized = policy.validateRepositoryPath(file.path());
                if (!normalized.equals(file.path()) || file.path().length() > 512 || file.content().length() > 524288
                        || policy.isSensitiveOrExcluded(file.path()) || !paths.add(normalized)) return "Unsafe or duplicate snapshot path";
            }
            for (var test : request.tests()) {
                String normalized = policy.validateRepositoryPath(test.path());
                if (!normalized.equals(test.path()) || test.path().length() > 512 || test.content().length() > 200000
                        || policy.isSensitiveOrExcluded(test.path()) || !paths.add(normalized)) return "Generated test would overwrite a file or has an unsafe path";
                String extension = request.language().equals("Java") ? ".java" : request.language().equals("Python") ? ".py"
                        : request.language().equals("TypeScript") ? ".ts" : ".js";
                if (!test.path().endsWith(extension)) return "Test extension does not match its execution language";
            }
        } catch (RuntimeException ex) { return "Invalid execution input"; }
        return null;
    }

    public SandboxResult validateResult(SandboxResult result) {
        Set<String> outcomes = Set.of("SUCCESS", "TEST_FAILURE", "COMPILATION_FAILURE", "DEPENDENCY_FAILURE", "NO_TESTS", "INVALID_REPORT", "TIMEOUT", "UNSUPPORTED", "INFRASTRUCTURE_FAILURE");
        if (result == null || result.outcome() == null || !outcomes.contains(result.outcome()) || result.output() == null || result.tests() == null || result.tests().size() > 1000)
            return SandboxResult.failure("INVALID_REPORT", "Malformed worker result");
        for (var test : result.tests()) {
            if (test == null || test.name() == null || test.name().isBlank() || test.name().length() > 512 || test.message() == null || test.message().length() > 4096
                    || test.status() == null || !Set.of("PASSED", "FAILED", "ERROR", "SKIPPED").contains(test.status()) || !Double.isFinite(test.seconds()) || test.seconds() < 0)
                return SandboxResult.failure("INVALID_REPORT", "Malformed test case");
        }
        if (result.outcome().equals("TEST_FAILURE") && result.tests().stream()
                .noneMatch(t -> t.status().equals("FAILED") || t.status().equals("ERROR"))) {
            return SandboxResult.failure("INVALID_REPORT", "Test failure has no failing case evidence");
        }
        if (result.outcome().equals("SUCCESS")) {
            if (!Integer.valueOf(0).equals(result.exitCode())) return SandboxResult.failure("INVALID_REPORT", "Success contradicts worker exit code");
            if (result.tests().stream().anyMatch(t -> t.status().equals("FAILED") || t.status().equals("ERROR"))) return SandboxResult.failure("INVALID_REPORT", "Success contradicts test failures");
            if (result.tests().stream().noneMatch(t -> t.status().equals("PASSED"))) return new SandboxResult("NO_TESTS", result.exitCode(), "No passing, non-skipped tests were reported", result.tests());
        }
        return new SandboxResult(result.outcome(), result.exitCode(), bounded(result.output(), 65536), result.tests(), result.coverage());
    }
    private String bounded(String value, int limit) { return value == null ? "" : value.substring(0, Math.min(limit, value.length())); }
}

package com.testpilot.testing.execution;

import com.testpilot.project.entity.CodeFile;
import com.testpilot.repository.entity.RepositoryArtifact;
import com.testpilot.testing.entity.*;
import com.testpilot.testing.execution.sandbox.*;
import com.testpilot.testing.generation.SourceInput;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Legacy Java workflows share the same offline container boundary as draft execution. */
@Service
public class TestExecutionService {
    private final SecureContainerExecutor executor;
    public TestExecutionService(SecureContainerExecutor executor) { this.executor = executor; }

    public TestExecutionOutcome executeTests(Long run, List<CodeFile> files, List<GeneratedTest> tests) {
        return executeTests(run, files, tests, () -> false, () -> {});
    }
    public TestExecutionOutcome executeTests(Long run, List<CodeFile> files, List<GeneratedTest> tests,
                                             BooleanSupplier cancelled, Runnable heartbeat) {
        return execute(run, files.stream().map(f -> new SourceInput(f.getFilePath(), f.getContent())).toList(), tests, cancelled, heartbeat);
    }
    public TestExecutionOutcome executeCatalog(Long run, List<RepositoryArtifact> catalog, List<GeneratedTest> tests,
                                               BooleanSupplier cancelled, Runnable heartbeat) {
        for (var file : catalog) {
            try {
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.getContent().getBytes(StandardCharsets.UTF_8)));
                if (!hash.equals(file.getContentHash())) return convert(run, SandboxResult.failure("INPUT_REJECTED", "Catalog content hash mismatch"));
            } catch (Exception ex) { return convert(run, SandboxResult.failure("INPUT_REJECTED", "Invalid catalog content")); }
        }
        return execute(run, catalog.stream().map(f -> new SourceInput(f.getPath(), f.getContent())).toList(), tests, cancelled, heartbeat);
    }

    private TestExecutionOutcome execute(Long run, List<SourceInput> files, List<GeneratedTest> tests,
                                         BooleanSupplier cancelled, Runnable heartbeat) {
        if (tests.isEmpty()) return convert(run, SandboxResult.failure("NO_TESTS", "No generated tests to execute"));
        var policy = new com.testpilot.common.validation.RepositoryPathPolicy();
        List<SandboxRequest.TestFile> testFiles = new ArrayList<>();
        try {
            for (var test : tests) {
                policy.validateGeneratedTestClass(test.getTestClass());
                testFiles.add(new SandboxRequest.TestFile("src/test/java/" + test.getTestClass().replace('.', '/') + ".java", test.getTestCode()));
            }
        } catch (RuntimeException ex) { return convert(run, SandboxResult.failure("INPUT_REJECTED", "Invalid generated test identity")); }
        String source = files.stream().filter(f -> f.path().endsWith(".java") && f.path().contains("src/main/java/"))
                .map(SourceInput::path).findFirst().orElse("");
        var request = new SandboxRequest("Java", "JUnit 5 / Mockito", source, testFiles, files);
        return convert(run, executor.execute(request, "testpilot-secure-" + UUID.randomUUID(), cancelled, heartbeat));
    }

    private TestExecutionOutcome convert(Long run, SandboxResult result) {
        TestExecutionOutcomeType type;
        try { type = TestExecutionOutcomeType.valueOf(result.outcome()); }
        catch (IllegalArgumentException ex) { type = TestExecutionOutcomeType.INFRASTRUCTURE_FAILURE; }
        var cases = result.tests().stream().map(t -> new TestResult(run,
                t.name().substring(0, Math.min(255, t.name().length())), TestResultStatus.valueOf(t.status()), t.message(), null, t.seconds())).toList();
        return new TestExecutionOutcome(type, cases, result.exitCode(), result.output(), "container", TestExecutionMetrics.unavailable());
    }
}

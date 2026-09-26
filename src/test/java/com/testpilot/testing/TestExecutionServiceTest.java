package com.testpilot.testing;
import com.testpilot.project.entity.CodeFile;
import com.testpilot.repository.entity.*;
import com.testpilot.testing.entity.GeneratedTest;
import com.testpilot.testing.execution.*;
import com.testpilot.testing.execution.sandbox.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TestExecutionServiceTest {
    private final SecureContainerExecutor sandbox = mock(SecureContainerExecutor.class);
    private final TestExecutionService service = new TestExecutionService(sandbox);
    @Test void delegatesCompilationToContainerAndPreservesFailure() {
        when(sandbox.execute(any(), anyString(), any(), any())).thenReturn(new SandboxResult("COMPILATION_FAILURE", 1, "Compilation failed", List.of()));
        var source = new CodeFile(1L, "Broken.java", "src/main/java/Broken.java", "class Broken { syntax error }");
        var test = new GeneratedTest(1L, "src/main/java/Broken.java", "BrokenTest", "class BrokenTest {}");
        var result = service.executeTests(1L, List.of(source), List.of(test));
        assertEquals(TestExecutionOutcomeType.COMPILATION_FAILURE, result.type());
        assertEquals("container", result.isolationBackend());
        assertFalse(result.completedTestProcess());
        verify(sandbox).execute(any(), startsWith("testpilot-secure-"), any(), any());
    }
    @Test void rejectsTamperedCatalogWithoutStartingAnything() {
        var file = new RepositoryArtifact(1L, "pom.xml", "sha", "0".repeat(64), RepositoryArtifactKind.BUILD_MANIFEST, 10, "<project/>");
        var result = service.executeCatalog(1L, List.of(file), List.of(), () -> false, () -> {});
        assertEquals(TestExecutionOutcomeType.INPUT_REJECTED, result.type());
        assertTrue(result.output().contains("content hash"));
        verifyNoInteractions(sandbox);
    }
    @Test void executesTheGeneratedTestsTargetRatherThanTheFirstNestedJavaFile() throws Exception {
        var nested = artifact("evaluation/benchmark/project/src/main/java/Example.java", "class Example {}");
        var target = artifact("src/main/java/com/testpilot/TestPilotApplication.java", "class TestPilotApplication {}");
        var test = new GeneratedTest(1L, target.getPath(), "com.testpilot.TestPilotApplicationTest", "class TestPilotApplicationTest {}");
        var captured = new AtomicReference<SandboxRequest>();
        when(sandbox.execute(any(), anyString(), any(), any())).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return new SandboxResult("NO_TESTS", 0, "No tests", List.of());
        });
        service.executeCatalog(1L, List.of(nested, target), List.of(test), () -> false, () -> {});
        assertEquals(target.getPath(), captured.get().sourcePath());
    }
    @Test void rejectsGeneratedTestsForDifferentSources() {
        var files = List.of(new CodeFile(1L, "A.java", "src/main/java/A.java", "class A {}"),
                new CodeFile(1L, "B.java", "src/main/java/B.java", "class B {}"));
        var tests = List.of(new GeneratedTest(1L, "src/main/java/A.java", "ATest", "class ATest {}"),
                new GeneratedTest(1L, "src/main/java/B.java", "BTest", "class BTest {}"));
        var result = service.executeTests(1L, files, tests);
        assertEquals(TestExecutionOutcomeType.INPUT_REJECTED, result.type());
        verifyNoInteractions(sandbox);
    }
    @Test void rejectsAmbiguousLegacySourceName() {
        var files = List.of(new CodeFile(1L, "A.java", "src/main/java/one/A.java", "class A {}"),
                new CodeFile(1L, "A.java", "src/main/java/two/A.java", "class A {}"));
        var test = new GeneratedTest(1L, "A.java", "ATest", "class ATest {}");
        var result = service.executeTests(1L, files, List.of(test));
        assertEquals(TestExecutionOutcomeType.INPUT_REJECTED, result.type());
        verifyNoInteractions(sandbox);
    }
    private RepositoryArtifact artifact(String path, String content) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        return new RepositoryArtifact(1L, path, "sha", hash, RepositoryArtifactKind.JAVA_SOURCE, content.length(), content);
    }
}

package com.testpilot.testing;
import com.testpilot.project.entity.CodeFile;
import com.testpilot.repository.entity.*;
import com.testpilot.testing.entity.GeneratedTest;
import com.testpilot.testing.execution.*;
import com.testpilot.testing.execution.sandbox.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TestExecutionServiceTest {
    private final SecureContainerExecutor sandbox = mock(SecureContainerExecutor.class);
    private final TestExecutionService service = new TestExecutionService(sandbox);
    @Test void delegatesCompilationToContainerAndPreservesFailure() {
        when(sandbox.execute(any(), anyString(), any(), any())).thenReturn(new SandboxResult("COMPILATION_FAILURE", 1, "Compilation failed", List.of()));
        var source = new CodeFile(1L, "Broken.java", "src/main/java/Broken.java", "class Broken { syntax error }");
        var test = new GeneratedTest(1L, "Broken.java", "BrokenTest", "class BrokenTest {}");
        var result = service.executeTests(1L, List.of(source), List.of(test));
        assertEquals(TestExecutionOutcomeType.COMPILATION_FAILURE, result.type());
        assertEquals("container", result.isolationBackend());
        assertFalse(result.completedTestProcess());
        verify(sandbox).execute(any(), startsWith("testpilot-secure-"), any(), any());
    }
    @Test void rejectsTamperedCatalogWithoutStartingAnything() {
        var file = new RepositoryArtifact(1L, "pom.xml", "sha", "0".repeat(64), RepositoryArtifactKind.BUILD_MANIFEST, 10, "<project/>");
        var result = service.executeCatalog(1L, List.of(file), List.of(), () -> false, () -> {});
        assertEquals(TestExecutionOutcomeType.INFRASTRUCTURE_FAILURE, result.type());
        assertTrue(result.output().contains("content hash"));
        verifyNoInteractions(sandbox);
    }
}

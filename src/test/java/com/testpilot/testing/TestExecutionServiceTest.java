package com.testpilot.testing;

import com.testpilot.common.validation.RepositoryPathPolicy;
import com.testpilot.project.entity.CodeFile;
import com.testpilot.testing.entity.GeneratedTest;
import com.testpilot.testing.execution.TestExecutionOutcomeType;
import com.testpilot.testing.execution.TestExecutionService;
import com.testpilot.testing.parser.SurefireReportParser;
import com.testpilot.repository.entity.RepositoryArtifact;
import com.testpilot.repository.entity.RepositoryArtifactKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestExecutionServiceTest {

    private final TestExecutionService service = new TestExecutionService(
            new SurefireReportParser(), new RepositoryPathPolicy());

    @Test
    void shouldReportCompilationFailureAsTypedFailedOutcome() {
        CodeFile brokenSource = new CodeFile(
                1L,
                "Broken.java",
                "src/main/java/com/example/Broken.java",
                "package com.example; public class Broken { public void broken( } }");
        GeneratedTest generatedTest = new GeneratedTest(
                1L,
                "Broken.java",
                "com.example.BrokenTest",
                "package com.example; public class BrokenTest {}");

        var outcome = service.executeTests(991_001L, List.of(brokenSource), List.of(generatedTest));

        assertEquals(TestExecutionOutcomeType.COMPILATION_FAILURE, outcome.type());
        assertNotNull(outcome.processExitCode());
        assertNotEquals(0, outcome.processExitCode());
        assertFalse(outcome.completedTestProcess());
        assertTrue(outcome.output().toLowerCase().contains("compilation"));
    }

    @Test
    void shouldRejectCatalogWhoseContentNoLongerMatchesIngestionHash() {
        RepositoryArtifact tamperedPom = new RepositoryArtifact(
                1L,
                "pom.xml",
                "object-sha",
                "0".repeat(64),
                RepositoryArtifactKind.BUILD_MANIFEST,
                10,
                "<project/>");

        var outcome = service.executeCatalog(
                991_002L,
                List.of(tamperedPom),
                List.of(),
                () -> false,
                () -> {});

        assertEquals(TestExecutionOutcomeType.INFRASTRUCTURE_FAILURE, outcome.type());
        assertTrue(outcome.output().contains("content hash"));
    }
}

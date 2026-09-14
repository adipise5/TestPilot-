package com.testpilot.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.observability.service.ModelInvocationRecorder;
import com.testpilot.observability.service.TestRunObservabilityService;
import com.testpilot.project.entity.CodeFile;
import com.testpilot.project.entity.Project;
import com.testpilot.project.repository.ProjectRepository;
import com.testpilot.rag.service.RagIngestionService;
import com.testpilot.rag.service.RagService;
import com.testpilot.testing.entity.TestRun;
import com.testpilot.testing.execution.TestExecutionMetrics;
import com.testpilot.testing.execution.TestExecutionOutcome;
import com.testpilot.testing.execution.TestExecutionOutcomeType;
import com.testpilot.testing.execution.job.ExecutionJobPersistenceService;
import com.testpilot.testing.repository.TestRunRepository;
import com.testpilot.testing.workflow.service.WorkflowRunService;
import com.testpilot.testing.workflow.service.WorkflowStepPersistenceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("h2")
class TestRunObservabilityIntegrationTest {

    @Autowired private ProjectRepository projects;
    @Autowired private TestRunRepository testRuns;
    @Autowired private WorkflowRunService workflows;
    @Autowired private WorkflowStepPersistenceService workflowSteps;
    @Autowired private ExecutionJobPersistenceService jobs;
    @Autowired private RagIngestionService ingestion;
    @Autowired private RagService rag;
    @Autowired private ModelInvocationRecorder modelInvocations;
    @Autowired private TestRunObservabilityService observability;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void aggregatesPersistedWorkflowWorkerRagAndModelEvidence() {
        Project project = projects.save(new Project("Observed", "phase seven", 404L));
        TestRun testRun = testRuns.save(new TestRun(project.getId()));
        var workflow = workflows.create(testRun.getId(), project.getId());
        var step = workflowSteps.begin(
                workflow.getId(), "codebase_mapper", "observability-step", "a".repeat(64)).step();
        workflowSteps.complete(step.getId(), "{}");
        workflows.complete(workflow.getId(), objectMapper.createObjectNode().put("decision", "COMPLETED"), false);

        var job = jobs.submit(testRun.getId());
        assertTrue(jobs.claim(job.getId(), "observability-worker"));
        jobs.complete(job.getId(), "observability-worker", new TestExecutionOutcome(
                TestExecutionOutcomeType.SUCCESS, List.of(), 0, "ok", "container",
                new TestExecutionMetrics(81.5, 66.0, "COLLECTED", "COLLECTED")));

        String commit = "dddddddddddddddddddddddddddddddddddddddd";
        ingestion.indexProject(project.getId(), commit, List.of(new CodeFile(
                project.getId(), "Observed.java", "src/main/java/demo/Observed.java",
                "package demo; public class Observed { public int value() { return 1; } }")), "plain-java");
        rag.retrieveForTesting(project.getId(), commit, "Observed value", testRun.getId());
        modelInvocations.observe(
                testRun.getId(), "test-generation-unit", "source and retrieved context",
                () -> Map.of("testClass", "demo.ObservedTest"));

        var result = observability.summarize(testRun.getId());
        assertNotNull(result.workflow());
        assertEquals(1, result.workflow().nodes().size());
        assertNotNull(result.execution());
        assertEquals("container", result.execution().isolationBackend());
        assertEquals(81.5, result.execution().lineCoveragePercent());
        assertEquals(1, result.rag().traceCount());
        assertFalse(result.rag().traceIds().isEmpty());
        assertEquals(1, result.models().invocationCount());
        assertTrue(result.models().inputTokens() > 0);
        assertEquals("mock-structured-v1", result.models().invocations().get(0).model());
    }
}

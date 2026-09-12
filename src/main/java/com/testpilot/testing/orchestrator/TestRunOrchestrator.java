package com.testpilot.testing.orchestrator;

import com.testpilot.testing.workflow.client.LangGraphClient;
import com.testpilot.testing.workflow.dto.WorkflowInvocationResponse;
import com.testpilot.testing.workflow.entity.WorkflowRun;
import com.testpilot.testing.workflow.service.WorkflowRunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class TestRunOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TestRunOrchestrator.class);

    private final LangGraphClient langGraphClient;
    private final WorkflowRunService workflowRunService;

    public TestRunOrchestrator(
            LangGraphClient langGraphClient,
            WorkflowRunService workflowRunService) {
        this.langGraphClient = langGraphClient;
        this.workflowRunService = workflowRunService;
    }

    @Async("testRunExecutor")
    public void orchestrateTestRunAsync(Long testRunId) {
        try {
            WorkflowRun workflow = workflowRunService.requireByTestRun(testRunId);
            WorkflowInvocationResponse response = langGraphClient.start(workflow);
            workflowRunService.applyInvocationResult(testRunId, response);
        } catch (Exception e) {
            log.error("LangGraph workflow failed for TestRun ID: {}", testRunId, e);
            workflowRunService.fail(testRunId, "LangGraph workflow invocation failed");
        }
    }

    @Async("testRunExecutor")
    public void resumeTestRunAsync(Long testRunId, boolean approved, String comment) {
        try {
            WorkflowRun workflow = workflowRunService.requireByTestRun(testRunId);
            WorkflowInvocationResponse response = langGraphClient.resume(workflow, approved, comment);
            workflowRunService.applyInvocationResult(testRunId, response);
        } catch (Exception e) {
            log.error("LangGraph workflow resume failed for TestRun ID: {}", testRunId, e);
            workflowRunService.fail(testRunId, "LangGraph workflow resume failed");
        }
    }
}

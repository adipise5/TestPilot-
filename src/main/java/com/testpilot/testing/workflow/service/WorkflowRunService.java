package com.testpilot.testing.workflow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.auth.security.UserPrincipal;
import com.testpilot.common.exception.InvalidRequestException;
import com.testpilot.common.exception.ResourceNotFoundException;
import com.testpilot.project.service.ProjectService;
import com.testpilot.testing.entity.TestRun;
import com.testpilot.testing.entity.TestRunStatus;
import com.testpilot.testing.repository.TestRunRepository;
import com.testpilot.testing.workflow.dto.*;
import com.testpilot.testing.workflow.entity.WorkflowRun;
import com.testpilot.testing.workflow.entity.WorkflowStatus;
import com.testpilot.testing.workflow.repository.WorkflowRunRepository;
import com.testpilot.testing.workflow.repository.WorkflowStepExecutionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class WorkflowRunService {

    public static final String GRAPH_VERSION = "testpilot-v1";

    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowStepExecutionRepository stepRepository;
    private final TestRunRepository testRunRepository;
    private final ProjectService projectService;
    private final ObjectMapper objectMapper;

    public WorkflowRunService(
            WorkflowRunRepository workflowRunRepository,
            WorkflowStepExecutionRepository stepRepository,
            TestRunRepository testRunRepository,
            ProjectService projectService,
            ObjectMapper objectMapper) {
        this.workflowRunRepository = workflowRunRepository;
        this.stepRepository = stepRepository;
        this.testRunRepository = testRunRepository;
        this.projectService = projectService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public WorkflowRun create(Long testRunId, Long projectId) {
        return workflowRunRepository.findByTestRunId(testRunId)
                .orElseGet(() -> workflowRunRepository.save(new WorkflowRun(
                        testRunId,
                        projectId,
                        "test-run-" + testRunId,
                        GRAPH_VERSION)));
    }

    @Transactional(readOnly = true)
    public WorkflowRun requireByTestRun(Long testRunId) {
        return workflowRunRepository.findByTestRunId(testRunId)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow not found for TestRun: " + testRunId));
    }

    @Transactional(readOnly = true)
    public WorkflowRun requireToolContext(Long testRunId, String threadId, String graphVersion) {
        WorkflowRun workflow = requireByTestRun(testRunId);
        if (!workflow.getThreadId().equals(threadId) || !workflow.getGraphVersion().equals(graphVersion)) {
            throw new InvalidRequestException("Workflow identity does not match the registered run");
        }
        return workflow;
    }

    @Transactional
    public void markNodeRunning(Long workflowId, TestRunStatus testRunStatus) {
        WorkflowRun workflow = workflowRunRepository.findById(workflowId)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow not found: " + workflowId));
        workflow.markRunning();
        workflowRunRepository.save(workflow);
        updateTestRunStatus(workflow.getTestRunId(), testRunStatus);
    }

    @Transactional
    public void recordCommit(Long workflowId, String commitSha) {
        WorkflowRun workflow = workflowRunRepository.findById(workflowId)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow not found: " + workflowId));
        workflow.recordCommit(commitSha);
        workflowRunRepository.save(workflow);
    }

    @Transactional
    public void applyInvocationResult(Long testRunId, WorkflowInvocationResponse response) {
        WorkflowRun workflow = requireByTestRun(testRunId);
        if (!workflow.getThreadId().equals(response.threadId())) {
            throw new InvalidRequestException("LangGraph returned a mismatched workflow thread");
        }
        switch (response.status()) {
            case "WAITING_FOR_APPROVAL" -> {
                String prompt = response.interrupt() == null
                        ? "Approve integration tests that require controlled external resources"
                        : response.interrupt().path("question").asText(
                                "Approve integration tests that require controlled external resources");
                workflow.requestApproval(prompt);
                updateTestRunStatus(testRunId, TestRunStatus.AWAITING_APPROVAL);
            }
            case "COMPLETED" -> {
                if (workflow.getStatus() != WorkflowStatus.COMPLETED) {
                    workflow.complete(workflow.getReportJson(), false);
                }
                updateTestRunStatus(testRunId, TestRunStatus.COMPLETED);
            }
            case "REJECTED" -> {
                if (workflow.getStatus() != WorkflowStatus.REJECTED) {
                    workflow.complete(workflow.getReportJson(), true);
                }
                updateTestRunStatus(testRunId, TestRunStatus.REJECTED);
            }
            case "FAILED" -> {
                if (workflow.getStatus() != WorkflowStatus.FAILED) {
                    workflow.fail("LangGraph workflow reported a failed terminal state");
                }
                updateTestRunStatus(testRunId, TestRunStatus.FAILED);
            }
            default -> workflow.markRunning();
        }
        workflowRunRepository.save(workflow);
    }

    @Transactional
    public WorkflowRun recordDecision(
            Long testRunId,
            WorkflowDecisionRequest request,
            UserPrincipal currentUser) {
        WorkflowRun workflow = requireByTestRun(testRunId);
        projectService.findProjectAndVerifyWriteAccess(workflow.getProjectId(), currentUser);
        try {
            workflow.recordDecision(request.approved(), request.comment());
        } catch (IllegalStateException e) {
            throw new InvalidRequestException(e.getMessage());
        }
        return workflowRunRepository.save(workflow);
    }

    @Transactional
    public void complete(Long workflowId, JsonNode report, boolean rejected) {
        WorkflowRun workflow = workflowRunRepository.findById(workflowId)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow not found: " + workflowId));
        String reportJson = writeJson(report);
        workflow.complete(reportJson, rejected);
        workflowRunRepository.save(workflow);
        updateTestRunStatus(
                workflow.getTestRunId(),
                rejected ? TestRunStatus.REJECTED : TestRunStatus.COMPLETED);
    }

    @Transactional
    public void finish(Long workflowId, JsonNode report, String terminalStatus) {
        WorkflowRun workflow = workflowRunRepository.findById(workflowId)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow not found: " + workflowId));
        String reportJson = writeJson(report);
        if ("FAILED".equals(terminalStatus)) {
            workflow.fail("Test execution did not complete", reportJson);
            workflowRunRepository.save(workflow);
            updateTestRunStatus(workflow.getTestRunId(), TestRunStatus.FAILED);
            return;
        }
        boolean rejected = "REJECTED".equals(terminalStatus);
        workflow.complete(reportJson, rejected);
        workflowRunRepository.save(workflow);
        updateTestRunStatus(
                workflow.getTestRunId(),
                rejected ? TestRunStatus.REJECTED : TestRunStatus.COMPLETED);
    }

    @Transactional
    public void fail(Long testRunId, String reason) {
        workflowRunRepository.findByTestRunId(testRunId).ifPresent(workflow -> {
            String safeReason = reason == null ? "LangGraph workflow failed" : truncate(reason, 500);
            workflow.fail(safeReason);
            workflowRunRepository.save(workflow);
        });
        testRunRepository.findById(testRunId).ifPresent(testRun -> {
            testRun.setStatus(TestRunStatus.FAILED);
            testRunRepository.save(testRun);
        });
    }

    @Transactional(readOnly = true)
    public WorkflowTraceResponse getTrace(Long testRunId, UserPrincipal currentUser) {
        WorkflowRun workflow = requireByTestRun(testRunId);
        projectService.findProjectAndVerifyReadAccess(workflow.getProjectId(), currentUser);
        List<WorkflowStepResponse> steps = stepRepository
                .findByWorkflowRunIdOrderByStartedAtAsc(workflow.getId())
                .stream()
                .map(WorkflowStepResponse::fromEntity)
                .toList();
        return WorkflowTraceResponse.from(workflow, readJson(workflow.getReportJson()), steps);
    }

    private void updateTestRunStatus(Long testRunId, TestRunStatus status) {
        TestRun testRun = testRunRepository.findById(testRunId)
                .orElseThrow(() -> new ResourceNotFoundException("TestRun not found: " + testRunId));
        testRun.setStatus(status);
        testRunRepository.save(testRun);
    }

    private String writeJson(JsonNode value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to store workflow report", e);
        }
    }

    private JsonNode readJson(String value) {
        try {
            return value == null ? null : objectMapper.readTree(value);
        } catch (Exception e) {
            return objectMapper.createObjectNode().put("error", "Stored report could not be decoded");
        }
    }

    private String truncate(String value, int max) {
        return value.substring(0, Math.min(max, value.length()));
    }
}

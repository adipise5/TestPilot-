package com.testpilot.testing.workflow.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.testpilot.testing.workflow.entity.WorkflowApprovalDecision;
import com.testpilot.testing.workflow.entity.WorkflowRun;
import com.testpilot.testing.workflow.entity.WorkflowStatus;

import java.time.LocalDateTime;
import java.util.List;

public record WorkflowTraceResponse(
        Long id,
        Long testRunId,
        String threadId,
        String graphVersion,
        String commitSha,
        WorkflowStatus status,
        WorkflowApprovalDecision approvalDecision,
        String approvalPrompt,
        String approvalComment,
        JsonNode report,
        String failureReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime completedAt,
        List<WorkflowStepResponse> steps
) {
    public static WorkflowTraceResponse from(
            WorkflowRun workflow,
            JsonNode report,
            List<WorkflowStepResponse> steps) {
        return new WorkflowTraceResponse(
                workflow.getId(),
                workflow.getTestRunId(),
                workflow.getThreadId(),
                workflow.getGraphVersion(),
                workflow.getCommitSha(),
                workflow.getStatus(),
                workflow.getApprovalDecision(),
                workflow.getApprovalPrompt(),
                workflow.getApprovalComment(),
                report,
                workflow.getFailureReason(),
                workflow.getCreatedAt(),
                workflow.getUpdatedAt(),
                workflow.getCompletedAt(),
                steps);
    }
}

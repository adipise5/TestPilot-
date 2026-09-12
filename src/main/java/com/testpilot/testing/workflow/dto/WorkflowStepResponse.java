package com.testpilot.testing.workflow.dto;

import com.testpilot.testing.workflow.entity.WorkflowStepExecution;
import com.testpilot.testing.workflow.entity.WorkflowStepStatus;

import java.time.LocalDateTime;

public record WorkflowStepResponse(
        Long id,
        String node,
        WorkflowStepStatus status,
        int attempts,
        String inputHash,
        String error,
        LocalDateTime startedAt,
        LocalDateTime completedAt
) {
    public static WorkflowStepResponse fromEntity(WorkflowStepExecution step) {
        return new WorkflowStepResponse(
                step.getId(),
                step.getNodeName(),
                step.getStatus(),
                step.getAttemptCount(),
                step.getInputHash(),
                step.getErrorMessage(),
                step.getStartedAt(),
                step.getCompletedAt());
    }
}

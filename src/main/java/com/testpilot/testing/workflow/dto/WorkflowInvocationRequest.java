package com.testpilot.testing.workflow.dto;

public record WorkflowInvocationRequest(
        int contractVersion,
        Long testRunId,
        Long projectId,
        String threadId,
        String graphVersion
) {}

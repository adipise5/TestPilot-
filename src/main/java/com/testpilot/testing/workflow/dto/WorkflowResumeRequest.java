package com.testpilot.testing.workflow.dto;

public record WorkflowResumeRequest(
        int contractVersion,
        boolean approved,
        String comment
) {}

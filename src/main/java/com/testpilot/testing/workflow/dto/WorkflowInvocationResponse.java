package com.testpilot.testing.workflow.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public record WorkflowInvocationResponse(
        int contractVersion,
        String threadId,
        String status,
        JsonNode interrupt,
        List<String> completedNodes
) {}

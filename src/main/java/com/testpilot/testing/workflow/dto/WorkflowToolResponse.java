package com.testpilot.testing.workflow.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record WorkflowToolResponse(
        int contractVersion,
        String node,
        boolean replayed,
        JsonNode updates
) {}

package com.testpilot.testing.workflow.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;

public record WorkflowToolRequest(
        @Min(1) @Max(1) int contractVersion,
        @NotNull @Positive Long testRunId,
        @NotBlank @Size(max = 100) String threadId,
        @NotBlank @Size(max = 40) String graphVersion,
        @NotBlank @Size(max = 180) String idempotencyKey,
        @NotNull JsonNode state
) {}

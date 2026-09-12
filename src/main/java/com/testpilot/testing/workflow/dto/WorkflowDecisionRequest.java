package com.testpilot.testing.workflow.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record WorkflowDecisionRequest(
        @NotNull Boolean approved,
        @Size(max = 500) String comment
) {}

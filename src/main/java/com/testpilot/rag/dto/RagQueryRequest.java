package com.testpilot.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

public record RagQueryRequest(
        @NotBlank(message = "Query text is required")
        String query,
        @Min(1) @Max(20) Integer topK
) {}

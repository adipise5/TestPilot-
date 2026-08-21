package com.testpilot.rag.dto;

import jakarta.validation.constraints.NotBlank;

public record RagQueryRequest(
        @NotBlank(message = "Query text is required")
        String query,
        Integer topK
) {}

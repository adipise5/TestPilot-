package com.testpilot.rag.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateKnowledgeDocumentRequest(
        @NotBlank(message = "Title is required")
        String title,

        @NotBlank(message = "Source is required")
        String source,

        @NotBlank(message = "Content is required")
        String content
) {}

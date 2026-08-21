package com.testpilot.project.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateCodeFileRequest(
        @NotBlank(message = "File name is required")
        String fileName,

        @NotBlank(message = "File path is required")
        String filePath,

        @NotBlank(message = "File content is required")
        String content
) {}

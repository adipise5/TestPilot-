package com.testpilot.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCodeFileRequest(
        @NotBlank(message = "File name is required")
        @Size(max = 255, message = "File name must not exceed 255 characters")
        String fileName,

        @NotBlank(message = "File path is required")
        @Size(max = 512, message = "File path must not exceed 512 characters")
        String filePath,

        @NotBlank(message = "File content is required")
        @Size(max = 500_000, message = "File content must not exceed 500,000 characters")
        String content
) {}

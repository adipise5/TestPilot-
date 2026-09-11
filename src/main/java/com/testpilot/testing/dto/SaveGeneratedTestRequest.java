package com.testpilot.testing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SaveGeneratedTestRequest(
        @NotBlank(message = "Source file name is required")
        @Size(max = 255, message = "Source file name must not exceed 255 characters")
        String sourceFile,

        @NotBlank(message = "Test class name is required")
        @Size(max = 255, message = "Test class name must not exceed 255 characters")
        String testClass,

        @NotBlank(message = "Test code is required")
        @Size(max = 500_000, message = "Test code must not exceed 500,000 characters")
        String testCode
) {}

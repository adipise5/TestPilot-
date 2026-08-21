package com.testpilot.testing.dto;

import jakarta.validation.constraints.NotBlank;

public record SaveGeneratedTestRequest(
        @NotBlank(message = "Source file name is required")
        String sourceFile,

        @NotBlank(message = "Test class name is required")
        String testClass,

        @NotBlank(message = "Test code is required")
        String testCode
) {}

package com.testpilot.rag.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ProjectRagQueryRequest(
        @NotBlank String query,
        @NotBlank
        @Pattern(regexp = "(?:[a-fA-F0-9]{40}|manual-[a-f0-9]{64})", message = "commitSha must be an immutable Git SHA or manual content revision")
        String commitSha,
        @Min(1) @Max(20) Integer topK,
        @Min(256) @Max(20_000) Integer tokenBudget
) {}

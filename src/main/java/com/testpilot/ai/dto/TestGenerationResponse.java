package com.testpilot.ai.dto;

import java.util.List;

public record TestGenerationResponse(
        String testClass,
        String explanation,
        List<TestCaseDto> tests,
        String fullTestCode
) {}

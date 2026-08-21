package com.testpilot.testing.dto;

import com.testpilot.testing.entity.TestResult;
import com.testpilot.testing.entity.TestResultStatus;

public record TestResultResponse(
        Long id,
        Long testRunId,
        String testName,
        TestResultStatus status,
        String errorMessage,
        String stackTrace,
        Double executionTime
) {
    public static TestResultResponse fromEntity(TestResult result) {
        return new TestResultResponse(
                result.getId(),
                result.getTestRunId(),
                result.getTestName(),
                result.getStatus(),
                result.getErrorMessage(),
                result.getStackTrace(),
                result.getExecutionTime()
        );
    }
}

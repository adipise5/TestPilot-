package com.testpilot.testing.execution.job;

import com.testpilot.testing.entity.TestResult;
import com.testpilot.testing.entity.TestResultStatus;

public record ExecutionResultSnapshot(
        String testName,
        TestResultStatus status,
        String errorMessage,
        String stackTrace,
        Double executionTime
) {
    public static ExecutionResultSnapshot from(TestResult result) {
        return new ExecutionResultSnapshot(
                result.getTestName(), result.getStatus(), result.getErrorMessage(),
                result.getStackTrace(), result.getExecutionTime());
    }

    public TestResult toEntity(Long testRunId) {
        return new TestResult(testRunId, testName, status, errorMessage, stackTrace, executionTime);
    }
}

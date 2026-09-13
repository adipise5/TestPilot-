package com.testpilot.testing.execution;

import com.testpilot.testing.entity.TestResult;

import java.util.List;

public record TestExecutionOutcome(
        TestExecutionOutcomeType type,
        List<TestResult> results,
        Integer processExitCode,
        String output,
        String isolationBackend,
        TestExecutionMetrics metrics
) {
    public TestExecutionOutcome(
            TestExecutionOutcomeType type,
            List<TestResult> results,
            Integer processExitCode,
            String output) {
        this(type, results, processExitCode, output, "local", TestExecutionMetrics.unavailable());
    }

    public boolean completedTestProcess() {
        return type == TestExecutionOutcomeType.SUCCESS || type == TestExecutionOutcomeType.TEST_FAILURE;
    }
}

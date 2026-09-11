package com.testpilot.testing.execution;

import com.testpilot.testing.entity.TestResult;

import java.util.List;

public record TestExecutionOutcome(
        TestExecutionOutcomeType type,
        List<TestResult> results,
        Integer processExitCode,
        String output
) {
    public boolean completedTestProcess() {
        return type == TestExecutionOutcomeType.SUCCESS || type == TestExecutionOutcomeType.TEST_FAILURE;
    }
}

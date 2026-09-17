package com.testpilot.testing.execution;

public enum TestExecutionOutcomeType {
    SUCCESS,
    TEST_FAILURE,
    COMPILATION_FAILURE,
    TIMEOUT,
    CANCELLED,
    UNSUPPORTED,
    DEPENDENCY_FAILURE,
    NO_TESTS,
    INVALID_REPORT,
    INFRASTRUCTURE_FAILURE
}

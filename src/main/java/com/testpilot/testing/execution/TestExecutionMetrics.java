package com.testpilot.testing.execution;

public record TestExecutionMetrics(
        Double lineCoveragePercent,
        Double mutationScorePercent,
        String coverageStatus,
        String mutationStatus
) {
    public static TestExecutionMetrics unavailable() {
        return new TestExecutionMetrics(null, null, "UNAVAILABLE", "UNAVAILABLE");
    }
}

package com.testpilot.testing.execution.job;

import com.testpilot.testing.execution.TestExecutionMetrics;
import com.testpilot.testing.execution.TestExecutionOutcome;
import com.testpilot.testing.execution.TestExecutionOutcomeType;

import java.util.List;

public record ExecutionJobResultPayload(
        TestExecutionOutcomeType type,
        List<ExecutionResultSnapshot> results,
        Integer processExitCode,
        String output,
        String isolationBackend,
        TestExecutionMetrics metrics
) {
    public static ExecutionJobResultPayload from(TestExecutionOutcome outcome) {
        return new ExecutionJobResultPayload(
                outcome.type(),
                outcome.results().stream().map(ExecutionResultSnapshot::from).toList(),
                outcome.processExitCode(), outcome.output(), outcome.isolationBackend(), outcome.metrics());
    }

    public TestExecutionOutcome toOutcome(Long testRunId) {
        return new TestExecutionOutcome(
                type,
                results.stream().map(result -> result.toEntity(testRunId)).toList(),
                processExitCode,
                output,
                isolationBackend,
                metrics);
    }
}

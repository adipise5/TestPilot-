package com.testpilot.testing.dto;

import com.testpilot.testing.entity.TestRun;
import com.testpilot.testing.entity.TestRunStatus;
import com.testpilot.testing.execution.TestExecutionOutcomeType;
import java.time.LocalDateTime;
import java.util.List;

public record TestRunResponse(
        Long id,
        Long projectId,
        TestRunStatus status,
        TestExecutionOutcomeType executionOutcome,
        Integer processExitCode,
        String executionOutput,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        List<GeneratedTestResponse> generatedTests,
        List<TestResultResponse> testResults
) {
    public static TestRunResponse fromEntity(TestRun testRun, List<GeneratedTestResponse> generatedTests, List<TestResultResponse> testResults) {
        return new TestRunResponse(
                testRun.getId(),
                testRun.getProjectId(),
                testRun.getStatus(),
                testRun.getExecutionOutcome(),
                testRun.getProcessExitCode(),
                testRun.getExecutionOutput(),
                testRun.getStartedAt(),
                testRun.getCompletedAt(),
                generatedTests,
                testResults
        );
    }
}

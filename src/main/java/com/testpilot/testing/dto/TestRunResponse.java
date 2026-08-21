package com.testpilot.testing.dto;

import com.testpilot.testing.entity.TestRun;
import com.testpilot.testing.entity.TestRunStatus;
import java.time.LocalDateTime;
import java.util.List;

public record TestRunResponse(
        Long id,
        Long projectId,
        TestRunStatus status,
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
                testRun.getStartedAt(),
                testRun.getCompletedAt(),
                generatedTests,
                testResults
        );
    }
}

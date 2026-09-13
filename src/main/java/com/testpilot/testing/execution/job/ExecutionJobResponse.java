package com.testpilot.testing.execution.job;

import com.testpilot.testing.execution.TestExecutionOutcomeType;

import java.time.LocalDateTime;

public record ExecutionJobResponse(
        Long id,
        ExecutionJobStatus status,
        int attempts,
        int maxAttempts,
        boolean cancelRequested,
        TestExecutionOutcomeType outcome,
        String isolationBackend,
        Double lineCoveragePercent,
        Double mutationScorePercent,
        String coverageStatus,
        String mutationStatus,
        String lastError,
        LocalDateTime heartbeatAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt
) {
    public static ExecutionJobResponse from(ExecutionJob job) {
        return new ExecutionJobResponse(
                job.getId(), job.getStatus(), job.getAttempts(), job.getMaxAttempts(),
                job.isCancelRequested(), job.getOutcome(), job.getIsolationBackend(),
                job.getLineCoveragePercent(), job.getMutationScorePercent(),
                job.getCoverageStatus(), job.getMutationStatus(), job.getLastError(),
                job.getHeartbeatAt(), job.getStartedAt(), job.getCompletedAt());
    }
}

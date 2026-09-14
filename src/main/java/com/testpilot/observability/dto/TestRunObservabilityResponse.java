package com.testpilot.observability.dto;

import com.testpilot.testing.execution.TestExecutionOutcomeType;
import com.testpilot.testing.execution.job.ExecutionJobStatus;
import com.testpilot.testing.workflow.entity.WorkflowStatus;

import java.time.LocalDateTime;
import java.util.List;

public record TestRunObservabilityResponse(
        Long testRunId,
        WorkflowSummary workflow,
        ExecutionSummary execution,
        RagSummary rag,
        ModelSummary models
) {
    public record WorkflowSummary(
            WorkflowStatus status,
            long durationMs,
            int totalRetries,
            List<NodeTiming> nodes) {}

    public record NodeTiming(
            String node,
            String status,
            int attempts,
            long durationMs) {}

    public record ExecutionSummary(
            Long jobId,
            ExecutionJobStatus status,
            TestExecutionOutcomeType outcome,
            String isolationBackend,
            int attempts,
            long queueTimeMs,
            long executionTimeMs,
            Double lineCoveragePercent,
            Double mutationScorePercent) {}

    public record RagSummary(
            int traceCount,
            int totalQueryTokens,
            int totalPackedTokens,
            long totalLatencyMs,
            double estimatedEmbeddingCostUsd,
            List<Long> traceIds) {}

    public record ModelSummary(
            int invocationCount,
            int failedInvocations,
            int inputTokens,
            int outputTokens,
            long totalLatencyMs,
            double estimatedCostUsd,
            List<ModelInvocation> invocations) {}

    public record ModelInvocation(
            Long id,
            String operation,
            String provider,
            String model,
            int inputTokens,
            int outputTokens,
            long latencyMs,
            double estimatedCostUsd,
            boolean successful,
            String errorType,
            LocalDateTime createdAt) {}
}

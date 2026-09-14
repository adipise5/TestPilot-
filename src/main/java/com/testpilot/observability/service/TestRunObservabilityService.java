package com.testpilot.observability.service;

import com.testpilot.observability.dto.TestRunObservabilityResponse;
import com.testpilot.observability.dto.TestRunObservabilityResponse.*;
import com.testpilot.observability.entity.ModelInvocationTrace;
import com.testpilot.observability.repository.ModelInvocationTraceRepository;
import com.testpilot.rag.entity.RagRetrievalTrace;
import com.testpilot.rag.repository.RagRetrievalTraceRepository;
import com.testpilot.testing.execution.job.ExecutionJobPersistenceService;
import com.testpilot.testing.workflow.entity.WorkflowRun;
import com.testpilot.testing.workflow.entity.WorkflowStepExecution;
import com.testpilot.testing.workflow.repository.WorkflowRunRepository;
import com.testpilot.testing.workflow.repository.WorkflowStepExecutionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class TestRunObservabilityService {

    private final WorkflowRunRepository workflows;
    private final WorkflowStepExecutionRepository steps;
    private final ExecutionJobPersistenceService executionJobs;
    private final RagRetrievalTraceRepository ragTraces;
    private final ModelInvocationTraceRepository modelTraces;

    public TestRunObservabilityService(
            WorkflowRunRepository workflows,
            WorkflowStepExecutionRepository steps,
            ExecutionJobPersistenceService executionJobs,
            RagRetrievalTraceRepository ragTraces,
            ModelInvocationTraceRepository modelTraces) {
        this.workflows = workflows;
        this.steps = steps;
        this.executionJobs = executionJobs;
        this.ragTraces = ragTraces;
        this.modelTraces = modelTraces;
    }

    @Transactional(readOnly = true)
    public TestRunObservabilityResponse summarize(Long testRunId) {
        return new TestRunObservabilityResponse(
                testRunId,
                workflowSummary(testRunId),
                executionSummary(testRunId),
                ragSummary(testRunId),
                modelSummary(testRunId));
    }

    private WorkflowSummary workflowSummary(Long testRunId) {
        return workflows.findByTestRunId(testRunId).map(workflow -> {
            List<NodeTiming> nodes = steps.findByWorkflowRunIdOrderByStartedAtAsc(workflow.getId()).stream()
                    .map(step -> new NodeTiming(
                            step.getNodeName(), step.getStatus().name(), step.getAttemptCount(),
                            duration(step.getStartedAt(), step.getCompletedAt())))
                    .toList();
            int retries = nodes.stream().mapToInt(node -> Math.max(0, node.attempts() - 1)).sum();
            return new WorkflowSummary(
                    workflow.getStatus(), duration(workflow.getCreatedAt(), workflow.getCompletedAt()),
                    retries, nodes);
        }).orElse(null);
    }

    private ExecutionSummary executionSummary(Long testRunId) {
        return executionJobs.findByTestRunId(testRunId).map(job -> new ExecutionSummary(
                job.getId(), job.getStatus(), job.getOutcome(), job.getIsolationBackend(), job.getAttempts(),
                duration(job.getCreatedAt(), job.getStartedAt()),
                duration(job.getStartedAt(), job.getCompletedAt()),
                job.getLineCoveragePercent(), job.getMutationScorePercent())).orElse(null);
    }

    private RagSummary ragSummary(Long testRunId) {
        List<RagRetrievalTrace> values = ragTraces.findByTestRunIdOrderByCreatedAtAsc(testRunId);
        return new RagSummary(
                values.size(),
                values.stream().mapToInt(RagRetrievalTrace::getQueryTokens).sum(),
                values.stream().mapToInt(RagRetrievalTrace::getPackedTokens).sum(),
                values.stream().mapToLong(RagRetrievalTrace::getLatencyMs).sum(),
                roundCost(values.stream().mapToDouble(RagRetrievalTrace::getEstimatedEmbeddingCostUsd).sum()),
                values.stream().map(RagRetrievalTrace::getId).toList());
    }

    private ModelSummary modelSummary(Long testRunId) {
        List<ModelInvocationTrace> values = modelTraces.findByTestRunIdOrderByCreatedAtAsc(testRunId);
        List<ModelInvocation> invocations = values.stream().map(trace -> new ModelInvocation(
                trace.getId(), trace.getOperation(), trace.getProvider(), trace.getModel(),
                trace.getInputTokens(), trace.getOutputTokens(), trace.getLatencyMs(),
                trace.getEstimatedCostUsd(), trace.isSuccessful(), trace.getErrorType(), trace.getCreatedAt()))
                .toList();
        return new ModelSummary(
                values.size(),
                (int) values.stream().filter(trace -> !trace.isSuccessful()).count(),
                values.stream().mapToInt(ModelInvocationTrace::getInputTokens).sum(),
                values.stream().mapToInt(ModelInvocationTrace::getOutputTokens).sum(),
                values.stream().mapToLong(ModelInvocationTrace::getLatencyMs).sum(),
                roundCost(values.stream().mapToDouble(ModelInvocationTrace::getEstimatedCostUsd).sum()),
                invocations);
    }

    private long duration(LocalDateTime start, LocalDateTime end) {
        if (start == null) return 0;
        LocalDateTime resolvedEnd = end == null ? LocalDateTime.now() : end;
        return Math.max(0, Duration.between(start, resolvedEnd).toMillis());
    }

    private double roundCost(double value) {
        return Math.round(value * 100_000_000.0) / 100_000_000.0;
    }
}

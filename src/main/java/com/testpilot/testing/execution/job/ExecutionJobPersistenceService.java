package com.testpilot.testing.execution.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.common.exception.ResourceNotFoundException;
import com.testpilot.testing.execution.TestExecutionOutcome;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class ExecutionJobPersistenceService {

    private final ExecutionJobRepository repository;
    private final ObjectMapper objectMapper;
    private final Duration leaseDuration;
    private final int maxAttempts;

    public ExecutionJobPersistenceService(
            ExecutionJobRepository repository,
            ObjectMapper objectMapper,
            @Value("${testpilot.execution.lease-seconds:30}") long leaseSeconds,
            @Value("${testpilot.execution.max-attempts:3}") int maxAttempts) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.leaseDuration = Duration.ofSeconds(Math.max(10, leaseSeconds));
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ExecutionJob submit(Long testRunId) {
        return repository.findByTestRunId(testRunId)
                .orElseGet(() -> repository.save(new ExecutionJob(testRunId, maxAttempts)));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(Long jobId, String workerId) {
        ExecutionJob job = locked(jobId);
        LocalDateTime now = LocalDateTime.now();
        boolean claimed = job.claim(workerId, now, now.plus(leaseDuration));
        if (claimed) {
            job.markRunning(workerId);
            repository.save(job);
        }
        return claimed;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void heartbeat(Long jobId, String workerId) {
        ExecutionJob job = locked(jobId);
        LocalDateTime now = LocalDateTime.now();
        job.heartbeat(workerId, now, now.plus(leaseDuration));
        repository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(Long jobId, String workerId, TestExecutionOutcome outcome) {
        ExecutionJob job = locked(jobId);
        job.complete(workerId, outcome, writePayload(outcome));
        repository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean retryOrFail(Long jobId, String workerId, TestExecutionOutcome outcome) {
        ExecutionJob job = locked(jobId);
        boolean retry = job.retryOrFail(workerId, outcome, writePayload(outcome));
        repository.save(job);
        return retry;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ExecutionJob requestCancellation(Long testRunId) {
        ExecutionJob job = repository.findByTestRunId(testRunId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution job not found for TestRun: " + testRunId));
        job.requestCancellation();
        return repository.save(job);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public boolean isCancellationRequested(Long jobId) {
        return repository.findById(jobId).map(ExecutionJob::isCancelRequested).orElse(true);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public ExecutionJob get(Long jobId) {
        return repository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution job not found: " + jobId));
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<ExecutionJob> findByTestRunId(Long testRunId) {
        return repository.findByTestRunId(testRunId);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<Long> queuedJobIds() {
        return repository.findByStatusInOrderByCreatedAtAsc(
                        List.of(ExecutionJobStatus.QUEUED, ExecutionJobStatus.RETRY_WAIT))
                .stream().map(ExecutionJob::getId).toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Long> recoverExpiredLeases() {
        List<ExecutionJob> expired = repository.findByStatusInAndLeaseExpiresAtBefore(
                List.of(ExecutionJobStatus.LEASED, ExecutionJobStatus.RUNNING), LocalDateTime.now());
        expired.forEach(job -> job.recoverExpired(LocalDateTime.now()));
        repository.saveAll(expired);
        return expired.stream().map(ExecutionJob::getId).toList();
    }

    public TestExecutionOutcome readOutcome(ExecutionJob job) {
        if (job.getResultJson() == null || job.getResultJson().isBlank()) {
            var fallbackType = job.getOutcome() != null
                    ? job.getOutcome()
                    : job.getStatus() == ExecutionJobStatus.CANCELLED
                        ? com.testpilot.testing.execution.TestExecutionOutcomeType.CANCELLED
                        : com.testpilot.testing.execution.TestExecutionOutcomeType.INFRASTRUCTURE_FAILURE;
            return new TestExecutionOutcome(
                    fallbackType, List.of(), job.getProcessExitCode(),
                    job.getBoundedOutput() == null ? "Execution job ended without process output" : job.getBoundedOutput(),
                    job.getIsolationBackend() == null ? "not-started" : job.getIsolationBackend(),
                    new com.testpilot.testing.execution.TestExecutionMetrics(
                            job.getLineCoveragePercent(), job.getMutationScorePercent(),
                            job.getCoverageStatus(), job.getMutationStatus()));
        }
        try {
            return objectMapper.readValue(job.getResultJson(), ExecutionJobResultPayload.class)
                    .toOutcome(job.getTestRunId());
        } catch (Exception e) {
            throw new IllegalStateException("Stored execution result could not be decoded", e);
        }
    }

    private ExecutionJob locked(Long jobId) {
        return repository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution job not found: " + jobId));
    }

    private String writePayload(TestExecutionOutcome outcome) {
        try {
            return objectMapper.writeValueAsString(ExecutionJobResultPayload.from(outcome));
        } catch (Exception e) {
            throw new IllegalStateException("Execution result could not be persisted", e);
        }
    }
}

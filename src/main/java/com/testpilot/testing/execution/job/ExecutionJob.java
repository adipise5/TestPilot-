package com.testpilot.testing.execution.job;

import com.testpilot.testing.execution.TestExecutionMetrics;
import com.testpilot.testing.execution.TestExecutionOutcome;
import com.testpilot.testing.execution.TestExecutionOutcomeType;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "execution_jobs",
        uniqueConstraints = @UniqueConstraint(name = "uk_execution_job_test_run", columnNames = "test_run_id"))
public class ExecutionJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "test_run_id", nullable = false)
    private Long testRunId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ExecutionJobStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "lease_owner", length = 100)
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;

    @Column(name = "heartbeat_at")
    private LocalDateTime heartbeatAt;

    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 40)
    private TestExecutionOutcomeType outcome;

    @Column(name = "process_exit_code")
    private Integer processExitCode;

    @Column(name = "bounded_output", columnDefinition = "TEXT")
    private String boundedOutput;

    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "isolation_backend", length = 30)
    private String isolationBackend;

    @Column(name = "line_coverage_percent")
    private Double lineCoveragePercent;

    @Column(name = "mutation_score_percent")
    private Double mutationScorePercent;

    @Column(name = "coverage_status", length = 40)
    private String coverageStatus;

    @Column(name = "mutation_status", length = 60)
    private String mutationStatus;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    protected ExecutionJob() {}

    public ExecutionJob(Long testRunId, int maxAttempts) {
        this.testRunId = testRunId;
        this.status = ExecutionJobStatus.QUEUED;
        this.maxAttempts = maxAttempts;
    }

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public boolean claim(String workerId, LocalDateTime now, LocalDateTime expiresAt) {
        boolean available = status == ExecutionJobStatus.QUEUED
                || status == ExecutionJobStatus.RETRY_WAIT
                || ((status == ExecutionJobStatus.LEASED || status == ExecutionJobStatus.RUNNING)
                    && leaseExpiresAt != null && leaseExpiresAt.isBefore(now));
        if (!available || cancelRequested || attempts >= maxAttempts) return false;
        status = ExecutionJobStatus.LEASED;
        attempts++;
        leaseOwner = workerId;
        leaseExpiresAt = expiresAt;
        heartbeatAt = now;
        if (startedAt == null) startedAt = now;
        return true;
    }

    public void markRunning(String workerId) {
        requireLease(workerId);
        status = ExecutionJobStatus.RUNNING;
    }

    public void heartbeat(String workerId, LocalDateTime now, LocalDateTime expiresAt) {
        requireLease(workerId);
        heartbeatAt = now;
        leaseExpiresAt = expiresAt;
    }

    public void requestCancellation() {
        if (isTerminal()) return;
        cancelRequested = true;
        if (status == ExecutionJobStatus.QUEUED || status == ExecutionJobStatus.RETRY_WAIT) {
            status = ExecutionJobStatus.CANCELLED;
            completedAt = LocalDateTime.now();
            clearLease();
        }
    }

    public void complete(String workerId, TestExecutionOutcome result, String serializedResult) {
        requireLease(workerId);
        outcome = result.type();
        processExitCode = result.processExitCode();
        boundedOutput = result.output();
        resultJson = serializedResult;
        isolationBackend = result.isolationBackend();
        applyMetrics(result.metrics());
        status = result.type() == TestExecutionOutcomeType.CANCELLED
                ? ExecutionJobStatus.CANCELLED
                : ExecutionJobStatus.COMPLETED;
        completedAt = LocalDateTime.now();
        clearLease();
    }

    public boolean retryOrFail(String workerId, TestExecutionOutcome result, String serializedResult) {
        requireLease(workerId);
        lastError = truncate(result.output(), 1000);
        outcome = result.type();
        processExitCode = result.processExitCode();
        boundedOutput = result.output();
        isolationBackend = result.isolationBackend();
        applyMetrics(result.metrics());
        if (attempts < maxAttempts && !cancelRequested) {
            status = ExecutionJobStatus.RETRY_WAIT;
            clearLease();
            return true;
        }
        status = cancelRequested ? ExecutionJobStatus.CANCELLED : ExecutionJobStatus.FAILED;
        resultJson = serializedResult;
        completedAt = LocalDateTime.now();
        clearLease();
        return false;
    }

    public void recoverExpired(LocalDateTime now) {
        if ((status != ExecutionJobStatus.LEASED && status != ExecutionJobStatus.RUNNING)
                || leaseExpiresAt == null || !leaseExpiresAt.isBefore(now)) return;
        lastError = "Worker lease expired; job recovered for another attempt";
        status = cancelRequested
                ? ExecutionJobStatus.CANCELLED
                : attempts < maxAttempts ? ExecutionJobStatus.QUEUED : ExecutionJobStatus.FAILED;
        if (status == ExecutionJobStatus.FAILED || status == ExecutionJobStatus.CANCELLED) {
            outcome = cancelRequested
                    ? TestExecutionOutcomeType.CANCELLED
                    : TestExecutionOutcomeType.INFRASTRUCTURE_FAILURE;
            boundedOutput = lastError;
            isolationBackend = "worker-lost";
            completedAt = now;
        }
        clearLease();
    }

    public boolean isTerminal() {
        return status == ExecutionJobStatus.COMPLETED
                || status == ExecutionJobStatus.FAILED
                || status == ExecutionJobStatus.CANCELLED;
    }

    private void applyMetrics(TestExecutionMetrics metrics) {
        if (metrics == null) return;
        lineCoveragePercent = metrics.lineCoveragePercent();
        mutationScorePercent = metrics.mutationScorePercent();
        coverageStatus = metrics.coverageStatus();
        mutationStatus = metrics.mutationStatus();
    }

    private void requireLease(String workerId) {
        if (leaseOwner == null || !leaseOwner.equals(workerId)
                || (status != ExecutionJobStatus.LEASED && status != ExecutionJobStatus.RUNNING)) {
            throw new IllegalStateException("Execution job lease is not owned by this worker");
        }
    }

    private void clearLease() {
        leaseOwner = null;
        leaseExpiresAt = null;
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.substring(0, Math.min(value.length(), max));
    }

    public Long getId() { return id; }
    public Long getTestRunId() { return testRunId; }
    public ExecutionJobStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public int getMaxAttempts() { return maxAttempts; }
    public String getLeaseOwner() { return leaseOwner; }
    public LocalDateTime getLeaseExpiresAt() { return leaseExpiresAt; }
    public LocalDateTime getHeartbeatAt() { return heartbeatAt; }
    public boolean isCancelRequested() { return cancelRequested; }
    public TestExecutionOutcomeType getOutcome() { return outcome; }
    public Integer getProcessExitCode() { return processExitCode; }
    public String getBoundedOutput() { return boundedOutput; }
    public String getResultJson() { return resultJson; }
    public String getIsolationBackend() { return isolationBackend; }
    public Double getLineCoveragePercent() { return lineCoveragePercent; }
    public Double getMutationScorePercent() { return mutationScorePercent; }
    public String getCoverageStatus() { return coverageStatus; }
    public String getMutationStatus() { return mutationStatus; }
    public String getLastError() { return lastError; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
}

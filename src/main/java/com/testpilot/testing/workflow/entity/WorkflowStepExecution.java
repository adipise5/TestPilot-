package com.testpilot.testing.workflow.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "workflow_step_executions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_workflow_step_idempotency",
                columnNames = {"workflow_run_id", "idempotency_key"}))
public class WorkflowStepExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_run_id", nullable = false)
    private Long workflowRunId;

    @Column(name = "node_name", nullable = false, length = 80)
    private String nodeName;

    @Column(name = "idempotency_key", nullable = false, length = 180)
    private String idempotencyKey;

    @Column(name = "input_hash", nullable = false, length = 64)
    private String inputHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkflowStepStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "output_json", columnDefinition = "TEXT")
    private String outputJson;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    protected WorkflowStepExecution() {}

    public WorkflowStepExecution(
            Long workflowRunId,
            String nodeName,
            String idempotencyKey,
            String inputHash) {
        this.workflowRunId = workflowRunId;
        this.nodeName = nodeName;
        this.idempotencyKey = idempotencyKey;
        this.inputHash = inputHash;
        this.status = WorkflowStepStatus.RUNNING;
        this.attemptCount = 1;
        this.startedAt = LocalDateTime.now();
    }

    public void retry() {
        status = WorkflowStepStatus.RUNNING;
        attemptCount++;
        errorMessage = null;
        startedAt = LocalDateTime.now();
        completedAt = null;
    }

    public void complete(String outputJson) {
        status = WorkflowStepStatus.COMPLETED;
        this.outputJson = outputJson;
        completedAt = LocalDateTime.now();
    }

    public void fail(String errorMessage) {
        status = WorkflowStepStatus.FAILED;
        this.errorMessage = errorMessage;
        completedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getWorkflowRunId() { return workflowRunId; }
    public String getNodeName() { return nodeName; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getInputHash() { return inputHash; }
    public WorkflowStepStatus getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public String getOutputJson() { return outputJson; }
    public String getErrorMessage() { return errorMessage; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
}

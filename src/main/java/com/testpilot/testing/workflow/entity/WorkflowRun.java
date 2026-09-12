package com.testpilot.testing.workflow.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "workflow_runs",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_workflow_test_run", columnNames = "test_run_id"),
                @UniqueConstraint(name = "uk_workflow_thread", columnNames = "thread_id")
        })
public class WorkflowRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "test_run_id", nullable = false)
    private Long testRunId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "thread_id", nullable = false, length = 100)
    private String threadId;

    @Column(name = "graph_version", nullable = false, length = 40)
    private String graphVersion;

    @Column(name = "commit_sha", length = 80)
    private String commitSha;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkflowStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_decision", nullable = false)
    private WorkflowApprovalDecision approvalDecision;

    @Column(name = "approval_prompt", length = 1000)
    private String approvalPrompt;

    @Column(name = "approval_comment", length = 500)
    private String approvalComment;

    @Column(name = "report_json", columnDefinition = "TEXT")
    private String reportJson;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    protected WorkflowRun() {}

    public WorkflowRun(Long testRunId, Long projectId, String threadId, String graphVersion) {
        this.testRunId = testRunId;
        this.projectId = projectId;
        this.threadId = threadId;
        this.graphVersion = graphVersion;
        this.status = WorkflowStatus.STARTING;
        this.approvalDecision = WorkflowApprovalDecision.NOT_REQUIRED;
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

    public void markRunning() {
        status = WorkflowStatus.RUNNING;
        failureReason = null;
    }

    public void recordCommit(String commitSha) {
        if (this.commitSha != null && !this.commitSha.equals(commitSha)) {
            throw new IllegalStateException("Workflow revision cannot change after intake");
        }
        this.commitSha = commitSha;
    }

    public void requestApproval(String prompt) {
        status = WorkflowStatus.WAITING_FOR_APPROVAL;
        approvalDecision = WorkflowApprovalDecision.PENDING;
        approvalPrompt = prompt;
    }

    public void recordDecision(boolean approved, String comment) {
        if (status != WorkflowStatus.WAITING_FOR_APPROVAL
                || approvalDecision != WorkflowApprovalDecision.PENDING) {
            throw new IllegalStateException("Workflow is not waiting for an approval decision");
        }
        approvalDecision = approved
                ? WorkflowApprovalDecision.APPROVED
                : WorkflowApprovalDecision.REJECTED;
        approvalComment = comment;
        status = WorkflowStatus.RUNNING;
    }

    public void complete(String reportJson, boolean rejected) {
        this.reportJson = reportJson;
        status = rejected ? WorkflowStatus.REJECTED : WorkflowStatus.COMPLETED;
        completedAt = LocalDateTime.now();
    }

    public void fail(String reason) {
        fail(reason, null);
    }

    public void fail(String reason, String reportJson) {
        status = WorkflowStatus.FAILED;
        failureReason = reason;
        this.reportJson = reportJson;
        completedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getTestRunId() { return testRunId; }
    public Long getProjectId() { return projectId; }
    public String getThreadId() { return threadId; }
    public String getGraphVersion() { return graphVersion; }
    public String getCommitSha() { return commitSha; }
    public WorkflowStatus getStatus() { return status; }
    public WorkflowApprovalDecision getApprovalDecision() { return approvalDecision; }
    public String getApprovalPrompt() { return approvalPrompt; }
    public String getApprovalComment() { return approvalComment; }
    public String getReportJson() { return reportJson; }
    public String getFailureReason() { return failureReason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
}

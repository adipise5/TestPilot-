package com.testpilot.testing.entity;

import com.testpilot.testing.execution.TestExecutionOutcomeType;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "test_runs")
public class TestRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TestRunStatus status;

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_outcome")
    private TestExecutionOutcomeType executionOutcome;

    @Column(name = "process_exit_code")
    private Integer processExitCode;

    @Column(name = "execution_output", columnDefinition = "TEXT")
    private String executionOutput;

    public TestRun() {}

    public TestRun(Long projectId) {
        this.projectId = projectId;
        this.status = TestRunStatus.PENDING;
    }

    @PrePersist
    protected void onCreate() {
        this.startedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public TestRunStatus getStatus() {
        return status;
    }

    public void setStatus(TestRunStatus status) {
        this.status = status;
        if (status == TestRunStatus.COMPLETED
                || status == TestRunStatus.FAILED
                || status == TestRunStatus.REJECTED) {
            this.completedAt = LocalDateTime.now();
        }
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public TestExecutionOutcomeType getExecutionOutcome() {
        return executionOutcome;
    }

    public Integer getProcessExitCode() {
        return processExitCode;
    }

    public String getExecutionOutput() {
        return executionOutput;
    }

    public void recordExecutionOutcome(
            TestExecutionOutcomeType executionOutcome,
            Integer processExitCode,
            String executionOutput) {
        this.executionOutcome = executionOutcome;
        this.processExitCode = processExitCode;
        this.executionOutput = executionOutput;
    }
}

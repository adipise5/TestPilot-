package com.testpilot.testing.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "test_results")
public class TestResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "test_run_id", nullable = false)
    private Long testRunId;

    @Column(name = "test_name", nullable = false)
    private String testName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TestResultStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "stack_trace", columnDefinition = "TEXT")
    private String stackTrace;

    @Column(name = "execution_time")
    private Double executionTime;

    public TestResult() {}

    public TestResult(Long testRunId, String testName, TestResultStatus status, String errorMessage, String stackTrace, Double executionTime) {
        this.testRunId = testRunId;
        this.testName = testName;
        this.status = status;
        this.errorMessage = errorMessage;
        this.stackTrace = stackTrace;
        this.executionTime = executionTime;
    }

    public Long getId() {
        return id;
    }

    public Long getTestRunId() {
        return testRunId;
    }

    public String getTestName() {
        return testName;
    }

    public TestResultStatus getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getStackTrace() {
        return stackTrace;
    }

    public Double getExecutionTime() {
        return executionTime;
    }
}

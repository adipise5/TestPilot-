package com.testpilot.observability.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "model_invocation_traces",
        indexes = @Index(name = "idx_model_trace_test_run", columnList = "test_run_id,created_at"))
public class ModelInvocationTrace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "test_run_id", nullable = false)
    private Long testRunId;

    @Column(nullable = false, length = 80)
    private String operation;

    @Column(nullable = false, length = 40)
    private String provider;

    @Column(nullable = false, length = 120)
    private String model;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    @Column(name = "estimated_cost_usd", nullable = false)
    private double estimatedCostUsd;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(nullable = false)
    private boolean successful;

    @Column(name = "error_type", length = 120)
    private String errorType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected ModelInvocationTrace() {}

    public ModelInvocationTrace(
            Long testRunId,
            String operation,
            String provider,
            String model,
            int inputTokens,
            int outputTokens,
            double estimatedCostUsd,
            long latencyMs,
            boolean successful,
            String errorType) {
        this.testRunId = testRunId;
        this.operation = operation;
        this.provider = provider;
        this.model = model;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.estimatedCostUsd = estimatedCostUsd;
        this.latencyMs = latencyMs;
        this.successful = successful;
        this.errorType = errorType;
    }

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getTestRunId() { return testRunId; }
    public String getOperation() { return operation; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public int getInputTokens() { return inputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public double getEstimatedCostUsd() { return estimatedCostUsd; }
    public long getLatencyMs() { return latencyMs; }
    public boolean isSuccessful() { return successful; }
    public String getErrorType() { return errorType; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}

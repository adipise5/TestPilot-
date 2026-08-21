package com.testpilot.failure.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "failure_analyses")
public class FailureAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "test_result_id", nullable = false)
    private Long testResultId;

    @Column(name = "root_cause", columnDefinition = "TEXT", nullable = false)
    private String rootCause;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    @Column(name = "affected_method", nullable = false)
    private String affectedMethod;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String explanation;

    @Column(nullable = false)
    private Double confidence;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public FailureAnalysis() {}

    public FailureAnalysis(Long testResultId, String rootCause, Severity severity, String affectedMethod, String explanation, Double confidence) {
        this.testResultId = testResultId;
        this.rootCause = rootCause;
        this.severity = severity;
        this.affectedMethod = affectedMethod;
        this.explanation = explanation;
        this.confidence = confidence;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getTestResultId() {
        return testResultId;
    }

    public String getRootCause() {
        return rootCause;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getAffectedMethod() {
        return affectedMethod;
    }

    public String getExplanation() {
        return explanation;
    }

    public Double getConfidence() {
        return confidence;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}

package com.testpilot.failure.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "fix_suggestions")
public class FixSuggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "failure_analysis_id", nullable = false)
    private Long failureAnalysisId;

    @Column(name = "original_code", columnDefinition = "TEXT", nullable = false)
    private String originalCode;

    @Column(name = "suggested_code", columnDefinition = "TEXT", nullable = false)
    private String suggestedCode;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String explanation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FixStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public FixSuggestion() {}

    public FixSuggestion(Long failureAnalysisId, String originalCode, String suggestedCode, String explanation) {
        this.failureAnalysisId = failureAnalysisId;
        this.originalCode = originalCode;
        this.suggestedCode = suggestedCode;
        this.explanation = explanation;
        this.status = FixStatus.PENDING;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getFailureAnalysisId() {
        return failureAnalysisId;
    }

    public String getOriginalCode() {
        return originalCode;
    }

    public String getSuggestedCode() {
        return suggestedCode;
    }

    public String getExplanation() {
        return explanation;
    }

    public FixStatus getStatus() {
        return status;
    }

    public void setStatus(FixStatus status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}

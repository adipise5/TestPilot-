package com.testpilot.failure.dto;

import com.testpilot.failure.entity.FailureAnalysis;
import com.testpilot.failure.entity.Severity;
import java.time.LocalDateTime;

public record FailureAnalysisResponse(
        Long id,
        Long testResultId,
        String rootCause,
        Severity severity,
        String affectedMethod,
        String explanation,
        Double confidence,
        LocalDateTime createdAt
) {
    public static FailureAnalysisResponse fromEntity(FailureAnalysis fa) {
        return new FailureAnalysisResponse(
                fa.getId(),
                fa.getTestResultId(),
                fa.getRootCause(),
                fa.getSeverity(),
                fa.getAffectedMethod(),
                fa.getExplanation(),
                fa.getConfidence(),
                fa.getCreatedAt()
        );
    }
}

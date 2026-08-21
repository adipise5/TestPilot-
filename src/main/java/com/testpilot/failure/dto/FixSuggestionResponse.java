package com.testpilot.failure.dto;

import com.testpilot.failure.entity.FixStatus;
import com.testpilot.failure.entity.FixSuggestion;
import java.time.LocalDateTime;

public record FixSuggestionResponse(
        Long id,
        Long failureAnalysisId,
        String originalCode,
        String suggestedCode,
        String explanation,
        FixStatus status,
        LocalDateTime createdAt
) {
    public static FixSuggestionResponse fromEntity(FixSuggestion fix) {
        return new FixSuggestionResponse(
                fix.getId(),
                fix.getFailureAnalysisId(),
                fix.getOriginalCode(),
                fix.getSuggestedCode(),
                fix.getExplanation(),
                fix.getStatus(),
                fix.getCreatedAt()
        );
    }
}

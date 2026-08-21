package com.testpilot.testing.dto;

import com.testpilot.testing.entity.GeneratedTest;
import java.time.LocalDateTime;

public record GeneratedTestResponse(
        Long id,
        Long testRunId,
        String sourceFile,
        String testClass,
        String testCode,
        LocalDateTime createdAt
) {
    public static GeneratedTestResponse fromEntity(GeneratedTest test) {
        return new GeneratedTestResponse(
                test.getId(),
                test.getTestRunId(),
                test.getSourceFile(),
                test.getTestClass(),
                test.getTestCode(),
                test.getCreatedAt()
        );
    }
}

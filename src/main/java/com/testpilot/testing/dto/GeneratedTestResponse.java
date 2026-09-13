package com.testpilot.testing.dto;

import com.testpilot.testing.entity.GeneratedTest;
import com.testpilot.testing.entity.TestLevel;
import java.time.LocalDateTime;

public record GeneratedTestResponse(
        Long id,
        Long testRunId,
        String sourceFile,
        String testClass,
        TestLevel testLevel,
        Long ragTraceId,
        String testCode,
        LocalDateTime createdAt
) {
    public static GeneratedTestResponse fromEntity(GeneratedTest test) {
        return new GeneratedTestResponse(
                test.getId(),
                test.getTestRunId(),
                test.getSourceFile(),
                test.getTestClass(),
                test.getTestLevel(),
                test.getRagTraceId(),
                test.getTestCode(),
                test.getCreatedAt()
        );
    }
}

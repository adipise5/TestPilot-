package com.testpilot.failure.agent;

import com.testpilot.ai.client.LlmClient;
import com.testpilot.failure.dto.FailureAnalysisResponse;
import com.testpilot.failure.entity.Severity;
import org.springframework.stereotype.Component;

@Component
public class FailureAnalysisAgent {

    private final LlmClient llmClient;

    public FailureAnalysisAgent(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public FailureAnalysisResponse analyzeFailure(
            String sourceCode,
            String testCode,
            String testName,
            String errorMessage,
            String stackTrace,
            String ragContext) {

        String systemInstruction = """
                You are an Expert Java Debugger and Test Failure Analysis Agent.
                Your task is to analyze why a JUnit 5 test failed by evaluating the source code, test code, error message, and stack trace.
                Explain the underlying root cause rather than repeating the stack trace.
                Return a structured JSON object with fields:
                - rootCause (string, concise summary of the bug)
                - severity (string: 'HIGH', 'MEDIUM', or 'LOW')
                - affectedMethod (string, method name in source code)
                - explanation (string, detailed breakdown of the failure)
                - confidence (number, e.g. 0.95)
                """;

        StringBuilder prompt = new StringBuilder();
        prompt.append("Test Name: ").append(testName).append("\n");
        prompt.append("Error Message: ").append(errorMessage != null ? errorMessage : "None").append("\n");
        prompt.append("Stack Trace:\n").append(stackTrace != null ? stackTrace : "None").append("\n\n");
        prompt.append("Source Code:\n").append(sourceCode).append("\n\n");
        prompt.append("Generated Test Code:\n").append(testCode).append("\n\n");

        if (ragContext != null && !ragContext.isBlank()) {
            prompt.append("Relevant Testing Knowledge:\n").append(ragContext).append("\n\n");
        }

        try {
            return llmClient.generateStructured(prompt.toString(), systemInstruction, FailureAnalysisResponse.class);
        } catch (Exception e) {
            // Fallback for mock/resilience
            return new FailureAnalysisResponse(
                    null,
                    null,
                    "Assertion Failed: " + (errorMessage != null ? errorMessage : "Expected value mismatch"),
                    Severity.HIGH,
                    extractAffectedMethod(testName),
                    "The test failed because the actual method output did not match expected test assertions.",
                    0.88,
                    null
            );
        }
    }

    private String extractAffectedMethod(String testName) {
        if (testName != null && testName.contains(".")) {
            String[] parts = testName.split("\\.");
            return parts[parts.length - 1];
        }
        return "Unknown";
    }
}

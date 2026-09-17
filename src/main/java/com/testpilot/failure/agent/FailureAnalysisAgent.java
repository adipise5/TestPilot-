package com.testpilot.failure.agent;

import com.testpilot.ai.client.LlmClient;
import com.testpilot.failure.dto.FailureAnalysisResponse;
import com.testpilot.failure.entity.Severity;
import com.testpilot.ai.prompt.PromptBoundary;
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

        String systemInstruction = PromptBoundary.UNTRUSTED_DATA_INSTRUCTION + """
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
        prompt.append("Analyze the supplied test failure.");
        prompt.append(PromptBoundary.section("TEST_NAME", testName, 500));
        prompt.append(PromptBoundary.section("ERROR_MESSAGE", errorMessage, 10_000));
        prompt.append(PromptBoundary.section("STACK_TRACE", stackTrace, 50_000));
        prompt.append(PromptBoundary.section("JAVA_SOURCE", sourceCode, 300_000));
        prompt.append(PromptBoundary.section("GENERATED_TEST", testCode, 200_000));

        if (ragContext != null && !ragContext.isBlank()) {
            prompt.append(PromptBoundary.section("RAG_CONTEXT", ragContext, 50_000));
        }

        try {
            return llmClient.generateStructured(prompt.toString(), systemInstruction, FailureAnalysisResponse.class);
        } catch (Exception e) {
            // Provider failure supplies no evidence for a root cause or affected method.
            return new FailureAnalysisResponse(null, null, "Analysis unavailable", Severity.LOW,
                    "Unknown", "The analysis provider failed. Review the captured diagnostic manually; no root cause has been established.",
                    0.0, null);
        }
    }
}

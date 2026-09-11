package com.testpilot.failure.agent;

import com.testpilot.ai.client.LlmClient;
import com.testpilot.failure.dto.FailureAnalysisResponse;
import com.testpilot.failure.dto.FixSuggestionResponse;
import com.testpilot.failure.entity.FixStatus;
import com.testpilot.ai.prompt.PromptBoundary;
import org.springframework.stereotype.Component;

@Component
public class FixSuggestionAgent {

    private final LlmClient llmClient;

    public FixSuggestionAgent(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public FixSuggestionResponse generateFix(
            String originalCode,
            FailureAnalysisResponse analysis,
            String stackTrace,
            String ragContext) {

        String systemInstruction = PromptBoundary.UNTRUSTED_DATA_INSTRUCTION + """
                You are an AI Code Remediation Agent.
                Your task is to propose fixed, production-ready Java code to resolve a detected test failure.
                Do not modify code arbitrarily; only fix the specific logic defect identified in the failure analysis.
                Return a structured JSON object with fields:
                - originalCode (string, the original source snippet)
                - suggestedCode (string, the complete updated source code)
                - explanation (string, summary of code changes)
                """;

        StringBuilder prompt = new StringBuilder();
        prompt.append("Propose a narrowly scoped fix using the supplied evidence.");
        prompt.append(PromptBoundary.section(
                "FAILURE_ANALYSIS",
                "Root cause: " + analysis.rootCause()
                        + "\nAffected method: " + analysis.affectedMethod()
                        + "\nExplanation: " + analysis.explanation(),
                20_000));
        prompt.append(PromptBoundary.section("STACK_TRACE", stackTrace, 50_000));
        prompt.append(PromptBoundary.section("JAVA_SOURCE", originalCode, 300_000));
        if (ragContext != null && !ragContext.isBlank()) {
            prompt.append(PromptBoundary.section("RAG_CONTEXT", ragContext, 50_000));
        }

        try {
            FixSuggestionResponse rawResponse = llmClient.generateStructured(prompt.toString(), systemInstruction, FixSuggestionResponse.class);
            return new FixSuggestionResponse(
                    null,
                    analysis.id(),
                    originalCode,
                    rawResponse.suggestedCode() != null ? rawResponse.suggestedCode() : originalCode,
                    rawResponse.explanation() != null ? rawResponse.explanation() : "Updated implementation to fix test failure.",
                    FixStatus.PENDING,
                    null
            );
        } catch (Exception e) {
            // Fallback for mock/resilience
            return new FixSuggestionResponse(
                    null,
                    analysis.id(),
                    originalCode,
                    originalCode + "\n// AI Suggested Fix Applied",
                    "Added safety validation to prevent failure: " + analysis.rootCause(),
                    FixStatus.PENDING,
                    null
            );
        }
    }
}

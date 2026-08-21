package com.testpilot.failure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.ai.client.MockLlmClient;
import com.testpilot.failure.agent.FixSuggestionAgent;
import com.testpilot.failure.dto.FailureAnalysisResponse;
import com.testpilot.failure.dto.FixSuggestionResponse;
import com.testpilot.failure.entity.FixStatus;
import com.testpilot.failure.entity.Severity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FixSuggestionAgentTest {

    @Test
    void shouldGenerateFixSuggestion() {
        MockLlmClient mockClient = new MockLlmClient(new ObjectMapper());
        FixSuggestionAgent agent = new FixSuggestionAgent(mockClient);

        String originalCode = "public int divide(int a, int b) { return a / b; }";
        FailureAnalysisResponse analysis = new FailureAnalysisResponse(1L, 1L, "Division by zero", Severity.HIGH, "divide", "No zero check", 0.95, null);

        FixSuggestionResponse response = agent.generateFix(originalCode, analysis, "ArithmeticException", null);

        assertNotNull(response);
        assertEquals(FixStatus.PENDING, response.status());
        assertNotNull(response.suggestedCode());
    }
}

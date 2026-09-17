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
    @Test void providerFailurePreservesSourceAndDoesNotClaimAFix() {
        var client = org.mockito.Mockito.mock(com.testpilot.ai.client.LlmClient.class);
        org.mockito.Mockito.when(client.generateStructured(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(FixSuggestionResponse.class)))
                .thenThrow(new IllegalStateException("offline"));
        var analysis = new FailureAnalysisResponse(1L, 1L, "Unknown", Severity.LOW, "Unknown", "", 0.0, null);
        var result = new FixSuggestionAgent(client).generateFix("original", analysis, "", null);
        assertEquals("original", result.suggestedCode());
        assertTrue(result.explanation().contains("unavailable"));
    }

}

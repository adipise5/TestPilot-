package com.testpilot.failure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.ai.client.MockLlmClient;
import com.testpilot.failure.agent.FailureAnalysisAgent;
import com.testpilot.failure.dto.FailureAnalysisResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FailureAnalysisAgentTest {

    @Test
    void shouldAnalyzeFailureDiagnostic() {
        MockLlmClient mockClient = new MockLlmClient(new ObjectMapper());
        FailureAnalysisAgent agent = new FailureAnalysisAgent(mockClient);

        String sourceCode = "public class Calculator { public int divide(int a, int b) { return a / b; } }";
        String testCode = "@Test void testDivide() { new Calculator().divide(5, 0); }";
        String stackTrace = "java.lang.ArithmeticException: / by zero at Calculator.divide(Calculator.java:2)";

        FailureAnalysisResponse response = agent.analyzeFailure(sourceCode, testCode, "divideTest", "/ by zero", stackTrace, null);

        assertNotNull(response);
        assertNotNull(response.rootCause());
        assertNotNull(response.severity());
        assertNotNull(response.affectedMethod());
    }
    @Test void providerFailureDoesNotInventRootCauseOrConfidence() {
        var client = org.mockito.Mockito.mock(com.testpilot.ai.client.LlmClient.class);
        org.mockito.Mockito.when(client.generateStructured(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(FailureAnalysisResponse.class)))
                .thenThrow(new IllegalStateException("offline"));
        var result = new FailureAnalysisAgent(client).analyzeFailure("source", "test", "Class.test", "timeout", "", null);
        assertEquals("Analysis unavailable", result.rootCause());
        assertEquals("Unknown", result.affectedMethod());
        assertEquals(0.0, result.confidence());
    }

}

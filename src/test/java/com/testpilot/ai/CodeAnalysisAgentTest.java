package com.testpilot.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.ai.agent.CodeAnalysisAgent;
import com.testpilot.ai.client.MockLlmClient;
import com.testpilot.ai.dto.CodeAnalysisResponse;
import com.testpilot.project.entity.CodeFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeAnalysisAgentTest {

    @Test
    void shouldAnalyzeCodeStructured() {
        MockLlmClient mockClient = new MockLlmClient(new ObjectMapper());
        CodeAnalysisAgent agent = new CodeAnalysisAgent(mockClient);

        CodeFile codeFile = new CodeFile(1L, "Calculator.java", "src/main/java/com/example/Calculator.java", "public class Calculator {}");
        CodeAnalysisResponse response = agent.analyzeCode(List.of(codeFile));

        assertNotNull(response);
        assertTrue(response.classes().contains("Calculator"));
        assertFalse(response.edgeCases().isEmpty());
    }
}

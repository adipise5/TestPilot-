package com.testpilot.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.ai.agent.TestGenerationAgent;
import com.testpilot.ai.client.MockLlmClient;
import com.testpilot.ai.dto.CodeAnalysisResponse;
import com.testpilot.ai.dto.TestGenerationResponse;
import com.testpilot.project.entity.CodeFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestGenerationAgentTest {

    @Test
    void shouldGenerateTestsStructured() {
        MockLlmClient mockClient = new MockLlmClient(new ObjectMapper());
        TestGenerationAgent agent = new TestGenerationAgent(mockClient);

        CodeFile codeFile = new CodeFile(1L, "Calculator.java", "src/main/java/com/example/Calculator.java", "package com.example; public class Calculator {}");
        CodeAnalysisResponse analysis = new CodeAnalysisResponse("Summary", List.of("Calculator"), List.of(), List.of("Overflow"), List.of(), List.of());

        TestGenerationResponse response = agent.generateTests(List.of(codeFile), analysis, null);

        assertNotNull(response);
        assertTrue(response.testClass().matches("com\\.example\\.TpCalculator_unit_[a-f0-9]{16}Test"));
        assertTrue(response.fullTestCode().contains(response.testClass().substring("com.example.".length())));
        assertTrue(response.fullTestCode().contains("@Disabled"));
    }
}

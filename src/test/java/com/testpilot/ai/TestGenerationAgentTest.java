package com.testpilot.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.ai.agent.TestGenerationAgent;
import com.testpilot.ai.client.MockLlmClient;
import com.testpilot.ai.client.LlmClient;
import com.testpilot.ai.dto.CodeAnalysisResponse;
import com.testpilot.ai.dto.TestGenerationResponse;
import com.testpilot.ai.dto.TestCaseDto;
import com.testpilot.project.entity.CodeFile;
import com.testpilot.testing.entity.TestLevel;
import com.testpilot.testing.generation.JavaTestAdapter;
import com.testpilot.testing.generation.SourceInput;
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

    @Test
    void derivesJavaTestMetadataFromActualAnnotatedMethods() {
        var source = new SourceInput("src/main/java/com/example/Calculator.java",
                "package com.example; public class Calculator { public int add(int a, int b) { return a + b; } }");
        var adapter = new JavaTestAdapter();
        var plan = adapter.plan(source, TestLevel.UNIT, List.of(source), "sha");
        String code = "package com.example; import org.junit.jupiter.api.Test; "
                + "import org.junit.jupiter.api.Tag; import static org.junit.jupiter.api.Assertions.*; "
                + "@Tag(\"unit\") class " + plan.testName().substring(plan.testName().lastIndexOf('.') + 1)
                + " { @Test @org.junit.jupiter.api.DisplayName(\"adds numbers\") "
                + "public void adds() { assertEquals(3, new Calculator().add(1, 2)); } }";
        LlmClient model = new LlmClient() {
            public String generate(String prompt, String instruction) { return ""; }
            @SuppressWarnings("unchecked")
            public <T> T generateStructured(String prompt, String instruction, Class<T> type) {
                return (T) new TestGenerationResponse(plan.testName(), "Addition", List.of(new TestCaseDto("wrongName", "")), code);
            }
            public String providerId() { return "openai-compatible"; }
        };
        var generated = new TestGenerationAgent(model).generate(plan, source, List.of(source), "", adapter);
        assertEquals("adds", generated.tests().get(0).name());
        assertEquals(1, generated.tests().size());
    }
}

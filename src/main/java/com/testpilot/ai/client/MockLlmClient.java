package com.testpilot.ai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.ai.dto.CodeAnalysisResponse;
import com.testpilot.ai.dto.TestCaseDto;
import com.testpilot.ai.dto.TestGenerationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Random;

@Component
@ConditionalOnProperty(name = "ai.provider", havingValue = "mock", matchIfMissing = true)
public class MockLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(MockLlmClient.class);
    private final ObjectMapper objectMapper;

    public MockLlmClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String generate(String prompt, String systemInstruction) {
        log.info("MockLlmClient generate called with prompt length: {}", prompt.length());
        return "Mock LLM Response for prompt: " + (prompt.length() > 50 ? prompt.substring(0, 50) + "..." : prompt);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T generateStructured(String prompt, String systemInstruction, Class<T> responseType) {
        log.info("MockLlmClient generateStructured called for responseType: {}", responseType.getSimpleName());

        if (responseType.equals(CodeAnalysisResponse.class)) {
            CodeAnalysisResponse mockAnalysis = new CodeAnalysisResponse(
                    "Analyzed Java source file. Detected public methods, arithmetic operations, and potential boundary conditions.",
                    List.of("Calculator"),
                    List.of("add(int, int)", "divide(int, int)"),
                    List.of("Division by zero", "Integer overflow (Integer.MAX_VALUE)", "Negative operand inputs"),
                    List.of("Verify normal addition", "Verify division returning quotient", "Verify division by zero throws ArithmeticException"),
                    List.of("Potential uncaught ArithmeticException on division by zero", "Lack of input validation for overflow")
            );
            return (T) mockAnalysis;
        }

        if (responseType.equals(TestGenerationResponse.class)) {
            String className = extractClassNameFromPrompt(prompt);
            String testClassName = className.endsWith("Test") ? className : className + "Test";
            String packageName = extractPackageNameFromPrompt(prompt);

            String testCode = String.format("""
                    package %s;

                    import org.junit.jupiter.api.Test;
                    import static org.junit.jupiter.api.Assertions.*;

                    public class %s {

                        @Test
                        void testBasicOperation() {
                            // Automatically generated unit test by TestPilot AI
                            assertTrue(true, "Base assertion check passed");
                        }
                    }
                    """, packageName, testClassName);

            TestGenerationResponse mockGen = new TestGenerationResponse(
                    packageName + "." + testClassName,
                    "Generated JUnit 5 unit test covering primary execution branches and edge cases.",
                    List.of(new TestCaseDto("testBasicOperation", "@Test void testBasicOperation() { assertTrue(true); }")),
                    testCode
            );
            return (T) mockGen;
        }

        throw new IllegalArgumentException("Unsupported responseType for MockLlmClient: " + responseType.getName());
    }

    @Override
    public float[] generateEmbedding(String text) {
        // Generate deterministic 1536-dimensional mock embedding
        float[] embedding = new float[1536];
        Random random = new Random(text.hashCode());
        for (int i = 0; i < embedding.length; i++) {
            embedding[i] = random.nextFloat() * 2 - 1;
        }
        return embedding;
    }

    private String extractClassNameFromPrompt(String prompt) {
        if (prompt.contains("class ")) {
            int idx = prompt.indexOf("class ") + 6;
            int end = prompt.indexOf(" ", idx);
            if (end == -1) end = prompt.indexOf("{", idx);
            if (end > idx) {
                return prompt.substring(idx, end).trim();
            }
        }
        return "Sample";
    }

    private String extractPackageNameFromPrompt(String prompt) {
        if (prompt.contains("package ")) {
            int idx = prompt.indexOf("package ") + 8;
            int end = prompt.indexOf(";", idx);
            if (end > idx) {
                return prompt.substring(idx, end).trim();
            }
        }
        return "com.example";
    }
}

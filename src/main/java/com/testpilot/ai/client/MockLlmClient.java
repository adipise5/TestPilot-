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
    public String providerId() {
        return "mock";
    }

    @Override
    public String modelId() {
        return "mock-structured-v1";
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
            if (prompt.startsWith("TESTPILOT_CONTROL: ")) return (T) adapterFixture(prompt);
            String className = extractClassNameFromPrompt(prompt);
            String levelSuffix = prompt.startsWith("TEST_LEVEL: INTEGRATION\n") ? "IntegrationTest"
                    : prompt.startsWith("TEST_LEVEL: MODULE\n") ? "ModuleTest" : "Test";
            String levelTag = prompt.startsWith("TEST_LEVEL: INTEGRATION\n") ? "integration"
                    : prompt.startsWith("TEST_LEVEL: MODULE\n") ? "module" : "unit";
            String testClassName = className.endsWith(levelSuffix) ? className : className + levelSuffix;
            String packageName = extractPackageNameFromPrompt(prompt);

            String testCode = String.format("""
                    package %s;

                    import org.junit.jupiter.api.Test;
                    import org.junit.jupiter.api.Tag;
                    import static org.junit.jupiter.api.Assertions.*;

                    public class %s {

                        @Tag("%s")
                        @Test
                        void testBasicOperation() {
                            // Deterministic offline test generated for the requested test level
                            assertTrue(true, "Base assertion check passed");
                        }
                    }
                    """, packageName, testClassName, levelTag);

            TestGenerationResponse mockGen = new TestGenerationResponse(
                    packageName + "." + testClassName,
                    "Generated JUnit 5 test for the requested workflow test level.",
                    List.of(new TestCaseDto("testBasicOperation", "@Test void testBasicOperation() { assertTrue(true); }")),
                    testCode
            );
            return (T) mockGen;
        }

        throw new IllegalArgumentException("Unsupported responseType for MockLlmClient: " + responseType.getName());
    }

    private TestGenerationResponse adapterFixture(String prompt) {
        try {
            var plan = objectMapper.readTree(prompt.substring("TESTPILOT_CONTROL: ".length(), prompt.indexOf('\n')));
            String name = plan.path("testName").asText();
            String level = plan.path("level").asText().toLowerCase(java.util.Locale.ROOT);
            String code;
            if (plan.path("language").asText().equals("Java")) {
                int dot = name.lastIndexOf('.');
                String pkg = dot < 0 ? "" : "package " + name.substring(0, dot) + ";\n";
                code = pkg + "import org.junit.jupiter.api.Test;\nimport org.junit.jupiter.api.Tag;\nimport org.junit.jupiter.api.Disabled;\n"
                        + "import static org.junit.jupiter.api.Assertions.fail;\npublic class " + name.substring(dot + 1)
                        + " {\n @Tag(\"" + level + "\")\n @Disabled(\"Mock provider scaffold; not a real test\")\n @Test\n void test_mock_scaffold() { fail(\"Configure a real provider\"); }\n}\n";
            } else if (plan.path("language").asText().equals("Python")) {
                code = "import pytest\npytestmark = pytest.mark." + level
                        + "\n@pytest.mark.skip(reason=\"Mock provider scaffold; not a real test\")\ndef test_mock_scaffold():\n    assert False, \"Configure a real provider\"\n";
            } else {
                String module = plan.path("framework").asText().equals("Jest") ? "@jest/globals" : "vitest";
                code = "import { describe, test, expect } from '" + module + "';\ndescribe('" + name + " [" + level
                        + "]', () => {\n  test.skip('test_mock_scaffold', () => { expect(false).toBe(true); });\n});\n";
            }
            return new TestGenerationResponse(name, "MOCK SCAFFOLD ONLY: intentionally skipped; no behavioral testing was generated.",
                    List.of(new TestCaseDto("test_mock_scaffold", "Mock scaffold; see fullTestCode")), code);
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("Invalid server generation control header", ex);
        }
    }

    private String extractClassNameFromPrompt(String prompt) {
        String sourcePrompt = extractSourceSection(prompt);
        if (sourcePrompt.contains("class ")) {
            int idx = sourcePrompt.indexOf("class ") + 6;
            int end = sourcePrompt.indexOf(" ", idx);
            if (end == -1) end = sourcePrompt.indexOf("{", idx);
            if (end > idx) {
                return sourcePrompt.substring(idx, end).trim();
            }
        }
        return "Sample";
    }

    private String extractPackageNameFromPrompt(String prompt) {
        String sourcePrompt = extractSourceSection(prompt);
        if (sourcePrompt.contains("package ")) {
            int idx = sourcePrompt.indexOf("package ") + 8;
            int end = sourcePrompt.indexOf(";", idx);
            if (end > idx) {
                return sourcePrompt.substring(idx, end).trim();
            }
        }
        return "com.example";
    }

    private String extractSourceSection(String prompt) {
        String marker = "BEGIN UNTRUSTED DATA: JAVA_SOURCE";
        int markerIndex = prompt.indexOf(marker);
        return markerIndex >= 0 ? prompt.substring(markerIndex + marker.length()) : prompt;
    }
}

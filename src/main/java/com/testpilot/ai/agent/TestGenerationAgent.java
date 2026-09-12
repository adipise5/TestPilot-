package com.testpilot.ai.agent;

import com.testpilot.ai.client.LlmClient;
import com.testpilot.ai.dto.CodeAnalysisResponse;
import com.testpilot.ai.dto.TestGenerationResponse;
import com.testpilot.ai.prompt.PromptBoundary;
import com.testpilot.project.entity.CodeFile;
import com.testpilot.testing.entity.TestLevel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class TestGenerationAgent {

    private final LlmClient llmClient;

    public TestGenerationAgent(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public TestGenerationResponse generateTests(List<CodeFile> sourceFiles, CodeAnalysisResponse analysis, String ragContext) {
        return generateTests(sourceFiles, analysis, ragContext, TestLevel.UNIT);
    }

    public TestGenerationResponse generateTests(
            List<CodeFile> sourceFiles,
            CodeAnalysisResponse analysis,
            String ragContext,
            TestLevel testLevel) {
        String combinedCode = sourceFiles.stream()
                .map(f -> "// File: " + f.getFilePath() + "\n" + f.getContent())
                .collect(Collectors.joining("\n\n"));

        String systemInstruction = PromptBoundary.UNTRUSTED_DATA_INSTRUCTION + """
                You are an expert AI Software Testing Agent specializing in JUnit 5 and Mockito.
                Your task is to generate complete, syntactically correct, and compilable JUnit 5 tests for the requested test level.
                Ensure test methods cover happy paths, edge cases, invalid inputs, and exceptions.
                Return a structured JSON object with fields:
                - testClass (string, fully qualified test class name)
                - explanation (string, summary of generated tests)
                - tests (list of test case objects with 'name' and 'code')
                - fullTestCode (string, complete Java file content including package and imports)
                """;

        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("TEST_LEVEL: ").append(testLevel.name()).append("\n");
        promptBuilder.append("Generate tests for the supplied source code. ")
                .append(levelGuidance(testLevel));
        promptBuilder.append(PromptBoundary.section("JAVA_SOURCE", combinedCode, 500_000));

        if (analysis != null) {
            promptBuilder.append(PromptBoundary.section(
                    "CODE_ANALYSIS",
                    analysis.summary() + "\nTarget edge cases: " + String.join(", ", analysis.edgeCases()),
                    20_000));
        }

        if (ragContext != null && !ragContext.isBlank()) {
            promptBuilder.append(PromptBoundary.section("RAG_CONTEXT", ragContext, 50_000));
        }

        promptBuilder.append("Generate one full JUnit 5 test class for the requested test level.");

        return llmClient.generateStructured(promptBuilder.toString(), systemInstruction, TestGenerationResponse.class);
    }

    private String levelGuidance(TestLevel level) {
        return switch (level) {
            case UNIT -> "Isolate one class and replace collaborators with test doubles where needed.";
            case MODULE -> "Exercise collaborating classes inside one application module without crossing deployable boundaries.";
            case INTEGRATION -> "Exercise framework wiring or controlled infrastructure boundaries; never call uncontrolled production services.";
        };
    }
}

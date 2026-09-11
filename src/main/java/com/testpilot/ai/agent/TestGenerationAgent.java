package com.testpilot.ai.agent;

import com.testpilot.ai.client.LlmClient;
import com.testpilot.ai.dto.CodeAnalysisResponse;
import com.testpilot.ai.dto.TestGenerationResponse;
import com.testpilot.ai.prompt.PromptBoundary;
import com.testpilot.project.entity.CodeFile;
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
        String combinedCode = sourceFiles.stream()
                .map(f -> "// File: " + f.getFilePath() + "\n" + f.getContent())
                .collect(Collectors.joining("\n\n"));

        String systemInstruction = PromptBoundary.UNTRUSTED_DATA_INSTRUCTION + """
                You are an expert AI Software Testing Agent specializing in JUnit 5 and Mockito.
                Your task is to generate complete, syntactically correct, and compilable JUnit 5 unit tests for the provided Java source code.
                Ensure test methods cover happy paths, edge cases, invalid inputs, and exceptions.
                Return a structured JSON object with fields:
                - testClass (string, fully qualified test class name)
                - explanation (string, summary of generated tests)
                - tests (list of test case objects with 'name' and 'code')
                - fullTestCode (string, complete Java file content including package and imports)
                """;

        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("Generate tests for the supplied source code.");
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

        promptBuilder.append("Generate full JUnit 5 unit test code.");

        return llmClient.generateStructured(promptBuilder.toString(), systemInstruction, TestGenerationResponse.class);
    }
}

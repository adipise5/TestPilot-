package com.testpilot.ai.agent;

import com.testpilot.ai.client.LlmClient;
import com.testpilot.ai.dto.CodeAnalysisResponse;
import com.testpilot.ai.dto.TestGenerationResponse;
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

        String systemInstruction = """
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
        promptBuilder.append("Source Code:\n").append(combinedCode).append("\n\n");

        if (analysis != null) {
            promptBuilder.append("Code Analysis Summary:\n").append(analysis.summary()).append("\n");
            promptBuilder.append("Target Edge Cases: ").append(String.join(", ", analysis.edgeCases())).append("\n\n");
        }

        if (ragContext != null && !ragContext.isBlank()) {
            promptBuilder.append("Testing Knowledge Base (RAG Context):\n").append(ragContext).append("\n\n");
        }

        promptBuilder.append("Generate full JUnit 5 unit test code.");

        return llmClient.generateStructured(promptBuilder.toString(), systemInstruction, TestGenerationResponse.class);
    }
}

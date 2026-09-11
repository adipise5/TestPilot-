package com.testpilot.ai.agent;

import com.testpilot.ai.client.LlmClient;
import com.testpilot.ai.dto.CodeAnalysisResponse;
import com.testpilot.ai.prompt.PromptBoundary;
import com.testpilot.project.entity.CodeFile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class CodeAnalysisAgent {

    private final LlmClient llmClient;

    public CodeAnalysisAgent(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public CodeAnalysisResponse analyzeCode(List<CodeFile> sourceFiles) {
        String combinedCode = sourceFiles.stream()
                .map(f -> "// File: " + f.getFilePath() + "\n" + f.getContent())
                .collect(Collectors.joining("\n\n"));

        String systemInstruction = PromptBoundary.UNTRUSTED_DATA_INSTRUCTION + """
                You are a Senior Java Software Engineer and Quality Assurance Architect.
                Your task is to analyze Java source code files and extract structural information, edge cases, and testing recommendations.
                Return a structured JSON object with fields:
                - summary (string)
                - classes (list of strings)
                - methods (list of strings)
                - edgeCases (list of strings)
                - testingRecommendations (list of strings)
                - potentialIssues (list of strings)
                """;

        String prompt = "Analyze the Java source code supplied below."
                + PromptBoundary.section("JAVA_SOURCE", combinedCode, 500_000);

        return llmClient.generateStructured(prompt, systemInstruction, CodeAnalysisResponse.class);
    }
}

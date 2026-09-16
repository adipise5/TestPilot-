package com.testpilot.ai.agent;

import com.testpilot.ai.client.LlmClient;
import com.testpilot.ai.dto.CodeAnalysisResponse;
import com.testpilot.ai.dto.TestGenerationResponse;
import com.testpilot.ai.prompt.PromptBoundary;
import com.testpilot.project.entity.CodeFile;
import com.testpilot.testing.entity.TestLevel;
import com.testpilot.testing.generation.*;
import com.testpilot.common.exception.InvalidRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

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
        if (sourceFiles.isEmpty()) throw new InvalidRequestException("Test generation requires source code");
        SourceInput source = new SourceInput(sourceFiles.get(0).getFilePath(), sourceFiles.get(0).getContent());
        var context = new java.util.ArrayList<SourceInput>();
        if (analysis != null) context.add(new SourceInput("ANALYSIS_CONTEXT",
                analysis.summary() + "\n" + String.valueOf(analysis.edgeCases())));
        sourceFiles.stream().map(f -> new SourceInput(f.getFilePath(), f.getContent())).forEach(context::add);
        JavaTestAdapter adapter = new JavaTestAdapter();
        var plan = adapter.plan(source, testLevel, context, "legacy-java");
        return generate(plan, source, context, ragContext, adapter);
    }

    public TestGenerationResponse generate(TestPlanItem plan, SourceInput source, List<SourceInput> context,
                                           String ragContext, LanguageTestAdapter adapter) {
        if (source.content().length() > 100_000) throw new InvalidRequestException("Source exceeds the 100,000-character generation limit; split the target first");
        String systemInstruction = PromptBoundary.UNTRUSTED_DATA_INSTRUCTION + """
                You are a software testing agent using the assigned language adapter.
                Generate a complete test file for the requested language, framework and level.
                Ensure test methods cover happy paths, edge cases, invalid inputs, and exceptions.
                Return a structured JSON object with fields:
                - testClass (string, exact server-assigned testName, even for non-Java files)
                - explanation (string, summary of generated tests)
                - tests (list of test case objects with 'name' and 'code')
                - fullTestCode (string, complete test file in the assigned language)
                """
                + adapter.instructions(plan)
                + " Metadata test names must be unique identifiers appearing in fullTestCode. Never return placeholders, skipped tests or vacuous assertions. "
                + "If the source does not provide enough information, do not invent missing production APIs. Return no tests so validation can reject the draft.";

        StringBuilder promptBuilder = new StringBuilder();
        try { promptBuilder.append("TESTPILOT_CONTROL: ").append(new ObjectMapper().writeValueAsString(plan)).append("\n"); }
        catch (java.io.IOException ex) { throw new IllegalStateException("Unable to encode generation plan", ex); }
        promptBuilder.append("Generate only the assigned target. ").append(plan.objective());
        promptBuilder.append(PromptBoundary.section("PRIMARY_SOURCE", source.content(), 100_000));
        StringBuilder contextText = new StringBuilder();
        context.stream().filter(s -> !s.path().equals(source.path())).limit(12).forEach(s -> {
            if (contextText.length() < 60_000) contextText.append("\nFile: ").append(s.path()).append("\n")
                    .append(s.content(), 0, Math.min(8_000, s.content().length()));
        });
        promptBuilder.append(PromptBoundary.section("BOUNDED_REPOSITORY_CONTEXT", contextText.toString(), 60_000));

        if (ragContext != null && !ragContext.isBlank()) {
            promptBuilder.append(PromptBoundary.section("RAG_CONTEXT", ragContext, 50_000));
        }

        promptBuilder.append("Generate one complete test file matching the assigned language, framework, identity and level. Context may be partial.");
        var response = llmClient.generateStructured(promptBuilder.toString(), systemInstruction, TestGenerationResponse.class);
        adapter.validate(plan, response, "mock".equals(llmClient.providerId()));
        return response;
    }

}

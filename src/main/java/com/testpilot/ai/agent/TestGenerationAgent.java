package com.testpilot.ai.agent;

import com.testpilot.ai.client.LlmClient;
import com.testpilot.ai.dto.CodeAnalysisResponse;
import com.testpilot.ai.dto.TestGenerationResponse;
import com.testpilot.ai.dto.TestCaseDto;
import com.testpilot.ai.prompt.PromptBoundary;
import com.testpilot.project.entity.CodeFile;
import com.testpilot.testing.entity.TestLevel;
import com.testpilot.testing.generation.*;
import com.testpilot.common.exception.InvalidRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.ArrayList;
import java.util.regex.Pattern;

@Component
public class TestGenerationAgent {

    private static final Pattern JAVA_TEST_METHOD = Pattern.compile(
            "@Test\\b(?:(?![{}]|@Test).){0,400}?\\bvoid\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(",
            Pattern.DOTALL);

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
        // Related symbols arrive from snapshot-scoped RAG. Avoid forwarding the
        // entire repository to every specialist for a single selected target.
        context.add(source);
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
                Keep the generated file concise: 3 to 8 focused test methods, with no duplicate scenarios.
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
            promptBuilder.append(PromptBoundary.section("RAG_CONTEXT", ragContext, 150_000));
        }

        promptBuilder.append("Generate one complete test file matching the assigned language, framework, identity and level. Context may be partial. Retrieved symbols are lexical candidates; inspect their definitions before calling them. Apply supplied language standards only when relevant to the target contract.");
        var response = llmClient.generateStructured(promptBuilder.toString(), systemInstruction, TestGenerationResponse.class);
        if (adapter instanceof JavaTestAdapter && !"mock".equals(llmClient.providerId()) && response != null
                && response.fullTestCode() != null) {
            String repairedCode = JavaTestStringEscapes.repair(response.fullTestCode());
            if (!repairedCode.equals(response.fullTestCode())) {
                response = new TestGenerationResponse(response.testClass(),
                        response.explanation() + " Java string escapes were normalized before validation.",
                        response.tests(), repairedCode);
            }
            // The source file is authoritative. Model-provided test metadata is
            // frequently stale or named differently from the actual @Test methods.
            var matcher = JAVA_TEST_METHOD.matcher(response.fullTestCode());
            List<TestCaseDto> actualTests = new ArrayList<>();
            while (matcher.find() && actualTests.size() <= 100) {
                actualTests.add(new TestCaseDto(matcher.group(1), matcher.group()));
            }
            if (actualTests.isEmpty() || actualTests.size() > 100) {
                throw new InvalidRequestException("Generated Java test file must declare 1–100 JUnit @Test methods");
            }
            response = new TestGenerationResponse(response.testClass(), response.explanation(), actualTests,
                    response.fullTestCode());
        }
        adapter.validate(plan, response, "mock".equals(llmClient.providerId()));
        return response;
    }

}

package com.testpilot.delivery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.rag.dto.RagRetrievalTraceResponse;
import com.testpilot.rag.entity.RagRetrievalTrace;
import com.testpilot.testing.entity.GeneratedTest;
import com.testpilot.testing.entity.TestResult;
import com.testpilot.testing.execution.job.ExecutionJob;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class DeliveryEvidenceBuilder {

    public static final String LIMITATIONS = "AI-generated tests require human code review. Validation is limited "
            + "to the analyzed immutable revision and the configured worker. Unit, module/component, and controlled "
            + "integration tests are included; system and browser end-to-end testing are outside project scope. "
            + "Token and cost values are estimates unless supplied by a provider adapter.";

    private static final int MAX_LOG_CHARS = 8_000;
    private static final int MAX_BODY_CHARS = 60_000;

    private final ObjectMapper objectMapper;

    public DeliveryEvidenceBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String validationSummary(ExecutionJob job, List<TestResult> results, int generatedFileCount) {
        long passed = results.stream().filter(result -> result.getStatus().name().equals("PASSED")).count();
        long skipped = results.stream().filter(result -> result.getStatus().name().equals("SKIPPED")).count();
        return "Validated " + generatedFileCount + " generated file(s); " + passed + " test(s) passed and "
                + skipped + " skipped; outcome=" + job.getOutcome()
                + "; lineCoverage=" + value(job.getLineCoveragePercent(), job.getCoverageStatus())
                + "; mutationScore=" + value(job.getMutationScorePercent(), job.getMutationStatus()) + ".";
    }

    public String pullRequestBody(
            Long testRunId,
            String baseCommitSha,
            String patchSha256,
            String validationSummary,
            String rollbackPath,
            List<GeneratedTest> generatedTests,
            ExecutionJob job,
            List<RagRetrievalTrace> traces) {
        StringBuilder body = new StringBuilder();
        body.append("## TestPilot generated-test delivery\n\n")
                .append("- TestPilot run: `#").append(testRunId).append("`\n")
                .append("- Analyzed commit: `").append(baseCommitSha).append("`\n")
                .append("- Patch SHA-256: `").append(patchSha256).append("`\n")
                .append("- Validation: **PASSED** — ").append(validationSummary).append("\n")
                .append("- Worker backend: `").append(safe(job.getIsolationBackend())).append("`\n")
                .append("- Worker attempts: `").append(job.getAttempts()).append("`\n\n")
                .append("### Generated tests\n\n");
        for (GeneratedTest test : generatedTests) {
            body.append("- `src/test/java/")
                    .append(test.getTestClass().replace('.', '/')).append(".java` — ")
                    .append(test.getTestLevel()).append("; source `")
                    .append(safe(test.getSourceFile())).append('`');
            if (test.getRagTraceId() != null) {
                body.append("; RAG trace `#").append(test.getRagTraceId()).append('`');
            }
            body.append('\n');
        }

        body.append("\n### Retrieval citations\n\n");
        Set<String> citations = new LinkedHashSet<>();
        for (RagRetrievalTrace trace : traces) {
            RagRetrievalTraceResponse.from(trace, objectMapper).citations().forEach(citation -> citations.add(
                    "- `" + safe(citation.source()) + "` lines " + citation.startLine() + "–"
                            + citation.endLine() + "; `" + safe(citation.uri()) + "`; chunk `"
                            + safe(citation.chunkKey()) + "`"));
        }
        if (citations.isEmpty()) {
            body.append("- No retrieval citations were attached to this run.\n");
        } else {
            citations.forEach(citation -> body.append(citation).append('\n'));
        }

        body.append("\n### Bounded validation log\n\n```text\n")
                .append(sanitizeLog(job.getBoundedOutput()))
                .append("\n```\n\n### Limitations\n\n")
                .append(LIMITATIONS)
                .append("\n\n### Rollback\n\n")
                .append(rollbackPath)
                .append("\n");
        return body.substring(0, Math.min(body.length(), MAX_BODY_CHARS));
    }

    private String sanitizeLog(String value) {
        if (value == null || value.isBlank()) return "No process output was captured.";
        String bounded = value.substring(0, Math.min(value.length(), MAX_LOG_CHARS));
        return bounded.replace("```", "` ` `");
    }

    private String value(Double metric, String status) {
        return metric == null ? safe(status) : String.format(java.util.Locale.ROOT, "%.2f%%", metric);
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) return "not-collected";
        return value.replace("`", "'").replace("\n", " ").replace("\r", " ");
    }
}

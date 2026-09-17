package com.testpilot.testing.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.testing.execution.sandbox.*;
import com.testpilot.testing.generation.TestDraft;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

@Component
public class TestReportBuilder {
    private final ObjectMapper mapper;
    public TestReportBuilder(ObjectMapper mapper) { this.mapper = mapper; }

    public ReportContext freeze(TestDraft draft, SandboxRequest input) {
        try {
            var metadata = mapper.readTree(draft.getResultJson());
            return new ReportContext(draft.getSnapshotId(), metadata.path("commitSha").asText("manual"),
                    input.language(), input.framework(), input.files().stream()
                    .filter(f -> f.path().equals(input.sourcePath())).findFirst().orElseThrow(), input.tests());
        } catch (java.io.IOException ex) { throw new IllegalStateException("Invalid draft metadata", ex); }
    }

    public TestReport build(Long projectId, Long draftId, ReportContext context, SandboxResult result, LocalDateTime started) {
        List<TestReport.Failure> failures = new ArrayList<>();
        for (var test : result.tests()) {
            if (test.status().equals("FAILED") || test.status().equals("ERROR")) {
                String text = test.message().toLowerCase(Locale.ROOT);
                String category = text.contains("timeout") || text.contains("timed out") ? "TEST_TIMEOUT"
                        : text.contains("assert") || text.contains("expected") || text.contains("tobe") ? "ASSERTION_MISMATCH"
                        : text.contains("fixture") || text.contains("setup") || text.contains("beforeeach") ? "FIXTURE_OR_SETUP"
                        : text.contains("exception") || text.contains("error") ? "UNEXPECTED_EXCEPTION" : "UNKNOWN_TEST_FAILURE";
                failures.add(new TestReport.Failure(test.name(), category, test.message(), failureGuidance(category)));
            }
        }
        List<TestReport.Finding> findings = new ArrayList<>();
        String diagnostic = result.output() + "\n" + String.join("\n", failures.stream().map(TestReport.Failure::evidence).toList());
        String source = context.source().content();
        String sourcePath = context.source().path();
        if (result.outcome().equals("TEST_FAILURE")) {
            findings.add(new TestReport.Finding("source-contract", "SOURCE_IMPROVEMENT", "MEDIUM", "REVIEW_REQUIRED",
                    "Check the source contract against the failing test",
                    "Compare the failing input, expected value and actual behavior in " + sourcePath
                            + ". Correct the implementation only if the requirement supports the test expectation; otherwise fix the test. A failed test alone does not identify which is wrong.",
                    new TestReport.Location(sourcePath, null, "")));
            if (Pattern.compile("(?i)(division by zero|zerodivisionerror|/ by zero)").matcher(diagnostic).find()) {
                scan(findings, sourcePath, source, "division-guard", "SOURCE_IMPROVEMENT", "MEDIUM", "HYPOTHESIS",
                        "Review the divisor contract", "Define behavior for a zero denominator before this division. Validate the denominator and raise a documented domain exception, or implement the specified result. Add tests for zero, negative and normal divisors.",
                        Pattern.compile("(?<!/)/(?![/*])"), 1);
            }
        }
        if (context.language().equals("Python")) {
            scan(findings, sourcePath, source, "broad-exception", "SOURCE_IMPROVEMENT", "LOW", "HEURISTIC",
                    "Review broad exception handling", "Catch the specific exception this operation can recover from; preserve or re-raise unexpected errors. Add a failure-path test to ensure errors are not silently hidden.", Pattern.compile("^\\s*except(?:\\s+Exception(?:\\s+as\\s+\\w+)?)?\\s*:"), 3);
        } else if (context.language().equals("Java")) {
            scan(findings, sourcePath, source, "broad-exception", "SOURCE_IMPROVEMENT", "LOW", "HEURISTIC",
                    "Review broad exception handling", "Check whether this boundary intentionally handles all exceptions. Otherwise catch the expected exception type and preserve the cause; verify the error path in a focused test.", Pattern.compile("catch\\s*\\(\\s*(?:Exception|Throwable)\\b"), 3);
        } else {
            scan(findings, sourcePath, source, "coercive-equality", "SOURCE_IMPROVEMENT", "LOW", "HEURISTIC",
                    "Review implicit type coercion", "If coercion is not part of this function's contract, normalize input types and use strict equality. Test numeric values, numeric strings and null-like inputs before changing the comparison.", Pattern.compile("(?<!=)==(?!=)|(?<!!)!=(?!=)"), 3);
        }
        for (var file : context.tests()) {
            scan(findings, file.path(), file.content(), "constant-assertion", "TEST_QUALITY", "MEDIUM", "HEURISTIC",
                    "Assertion may not exercise production behavior", "Replace this constant/self-comparison with an assertion on a real production call and an independently derived expected result.",
                    Pattern.compile("assertTrue\\(\\s*true|assert\\s+True\\b|assert\\s+(\\d+)\\s*==\\s*\\1\\b|expect\\(\\s*(true|\\d+)\\s*\\)\\.toBe\\(\\s*\\2\\s*\\)"), 5);
            scan(findings, file.path(), file.content(), "sleep", "TEST_QUALITY", "LOW", "HEURISTIC",
                    "Timing-dependent test", "Replace a fixed sleep with a deterministic clock, fake timers, or an explicit completion signal. Keep any timeout bounded and test the failure path.",
                    Pattern.compile("Thread\\.sleep|time\\.sleep|setTimeout\\s*\\("), 3);
            scan(findings, file.path(), file.content(), "skipped-source", "TEST_QUALITY", "MEDIUM", "HEURISTIC",
                    "Skipped test declared", "Implement or enable this case before counting it as validation. Keep deliberate exclusions documented in the report.",
                    Pattern.compile("@Disabled|pytest\\.mark\\.skip|(?:test|it|describe)\\.(?:skip|todo)\\s*\\("), 3);
        }
        long skipped = count(result, "SKIPPED");
        if (skipped > 0) findings.add(new TestReport.Finding("skipped-results", "TEST_QUALITY", "MEDIUM", "OBSERVED",
                "Skipped cases provide no passing evidence", "Review the " + skipped + " skipped cases and enable the intended behavioral checks.", null));
        var coverage = result.coverage();
        if (coverage.status().equals("MEASURED") && !coverage.missingLines().isEmpty()) {
            int line = coverage.missingLines().get(0);
            findings.add(new TestReport.Finding("uncovered-source", "TEST_QUALITY", "MEDIUM", "OBSERVED",
                    coverage.executedLines().isEmpty() ? "Selected source was not exercised" : "Executable source lines remain uncovered",
                    "Add an input that reaches this uncovered path, then assert its result or side effect. Review the report's missing-line list; line coverage does not establish branch or assertion quality.",
                    location(sourcePath, source, line)));
        }
        String classification = switch (result.outcome()) {
            case "SUCCESS" -> "NONE";
            case "TEST_FAILURE" -> "TEST_FAILURE";
            case "COMPILATION_FAILURE" -> "COMPILATION";
            case "DEPENDENCY_FAILURE" -> "DEPENDENCY";
            case "TIMEOUT" -> "EXECUTION_TIMEOUT";
            case "NO_TESTS" -> "NO_EXECUTED_TESTS";
            default -> result.outcome();
        };
        boolean measured = coverage.status().equals("MEASURED");
        var summary = new TestReport.Summary((int)count(result, "PASSED"), (int)count(result, "FAILED"),
                (int)count(result, "ERROR"), (int)skipped, classification, nextStep(result.outcome()));
        return new TestReport("testpilot-report-v1", projectId, draftId, context.snapshotId(), context.commitSha(),
                context.language(), context.framework(), started, LocalDateTime.now(),
                new TestReport.Artifact(sourcePath, hash(source)), context.tests().stream()
                .map(t -> new TestReport.Artifact(t.path(), hash(t.content()))).toList(), result, summary,
                List.copyOf(failures), findings.stream().limit(50).toList(),
                new TestReport.Coverage(coverage.status(), coverage.tool(), "SELECTED_SOURCE", sourcePath,
                        measured ? coverage.executedLines().size() : null,
                        measured ? coverage.executedLines().size() + coverage.missingLines().size() : null,
                        coverage.percent(), coverage.missingLines(), coverage.note()),
                "deterministic-evidence-v1", List.of(
                        "Suggestions are review guidance, not verified fixes; no source changes or model calls were made.",
                        "Static findings use bounded lexical rules on the selected source and generated tests, not repository-wide analysis; comments/strings may cause false positives.",
                        "Failure subcategories are diagnostic heuristics. Assertions, fixtures and the source contract must be reviewed before assigning blame.",
                        "Coverage is tool-reported line evidence for one source file; no branch/mutation measurement or baseline delta is claimed.",
                        "Repository tests can falsify reports. Passing cases and coverage are not proof of test usefulness.",
                        "This report is frozen to one attempt; an explicit retry replaces the saved report. Download it to retain that attempt."));
    }

    private long count(SandboxResult result, String status) { return result.tests().stream().filter(t -> status.equals(t.status())).count(); }
    private void scan(List<TestReport.Finding> findings, String path, String code, String id, String category,
                      String severity, String basis, String title, String guidance, Pattern pattern, int limit) {
        String[] lines = code.split("\\R", -1);
        int count = 0;
        for (int i = 0; i < Math.min(lines.length, 50000) && count < limit && findings.size() < 50; i++) {
            if (pattern.matcher(lines[i]).find()) {
                findings.add(new TestReport.Finding(id + ":" + path + ":" + (i+1), category, severity, basis,
                        title, guidance, location(path, code, i+1)));
                count++;
            }
        }
    }
    private TestReport.Location location(String path, String code, int line) {
        String[] lines = code.split("\\R", -1);
        if (line < 1 || line > lines.length) return new TestReport.Location(path, null, "");
        return new TestReport.Location(path, line, lines[line-1].substring(0, Math.min(500, lines[line-1].length())));
    }
    private String failureGuidance(String category) {
        return switch (category) {
            case "ASSERTION_MISMATCH" -> "Check expected versus actual values against the intended contract before changing source or assertions.";
            case "TEST_TIMEOUT" -> "Check for blocked work or a missing await/completion signal; use deterministic synchronization before raising the timeout.";
            case "FIXTURE_OR_SETUP" -> "Repair fixture initialization, teardown or mocks before attributing the failure to production logic.";
            case "UNEXPECTED_EXCEPTION" -> "Inspect the exception and failing input; decide whether the source needs validation or the test should assert the documented exception.";
            default -> "Inspect the captured diagnostic; the available evidence does not establish a root cause.";
        };
    }
    private String nextStep(String outcome) {
        return switch (outcome) {
            case "SUCCESS" -> "Review assertions and uncovered lines; passing execution alone does not prove correctness.";
            case "TEST_FAILURE" -> "Review the classified cases and source/test contract before applying a fix.";
            case "COMPILATION_FAILURE" -> "Fix syntax, types or build configuration using compiler diagnostics, then rerun.";
            case "DEPENDENCY_FAILURE" -> "Add the required compatible dependency to a reviewed worker image; runtime package installation remains disabled.";
            case "UNSUPPORTED" -> "Use a supported language/framework/layout; this attempt did not validate the source.";
            case "NO_TESTS" -> "Check discovery and skipped cases, then run at least one behavioral test.";
            case "TIMEOUT" -> "Inspect loops, blocking work and test synchronization within the fixed execution deadline.";
            case "CAPACITY_EXCEEDED" -> "Retry after an active execution finishes.";
            case "CANCELLED" -> "Execution was cancelled; rerun explicitly when ready.";
            case "INPUT_REJECTED" -> "Refresh the repository and regenerate the draft from a valid snapshot.";
            default -> "Check Docker, the reviewed image and worker/report diagnostics; no source defect is inferred.";
        };
    }
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}

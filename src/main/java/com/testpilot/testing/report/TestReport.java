package com.testpilot.testing.report;

import com.testpilot.testing.execution.sandbox.*;
import java.time.LocalDateTime;
import java.util.List;

public record TestReport(String schemaVersion, Long projectId, Long draftId, String snapshotId, String commitSha,
                         String language, String framework, LocalDateTime executionStartedAt, LocalDateTime generatedAt,
                         Artifact source, List<Artifact> generatedTests, SandboxResult execution,
                         Summary summary, List<Failure> failures, List<Finding> findings, Coverage coverage,
                         String analysisMethod, List<String> limitations) {
    public record Artifact(String path, String sha256) {}
    public record Summary(int passed, int failed, int errors, int skipped, String classification, String nextStep) {}
    public record Failure(String testName, String category, String evidence, String guidance) {}
    public record Location(String path, Integer line, String snippet) {}
    public record Finding(String id, String category, String severity, String basis, String title,
                          String guidance, Location location) {}
    public record Coverage(String status, String tool, String scope, String sourcePath, Integer coveredLines,
                           Integer totalLines, Double linePercent, List<Integer> missingLines, String note) {}
}

package com.testpilot.review;

import java.util.List;

/** Immutable review output. Evidence anchors are server-validated; conclusions remain AI proposals. */
public record ReviewReport(String schemaVersion, String snapshotId, String commitSha, String provider, String model,
                           String status, int batchOffset, Integer nextBatchOffset, int totalBatches, List<FileScope> files, List<Finding> findings, int rejectedFindings,
                           int successfulBatches, int failedBatches, List<String> limitations) {
    public record FileScope(String path, String language, String sha256, int lines, String status, String reason) {}
    public record Evidence(String path, int startLine, int endLine, String snippet) {}
    public record Finding(String kind, String category, String severity, String title, String explanation,
                          String guidance, List<Evidence> evidence) {}
    public record BatchResponse(List<String> reviewedPaths, List<Finding> findings) {}
}

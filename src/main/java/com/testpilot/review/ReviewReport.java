package com.testpilot.review;

import java.util.List;
import com.testpilot.rag.context.ContextBundle;

/** Immutable review output. Evidence anchors are server-validated; conclusions remain AI proposals. */
public record ReviewReport(String schemaVersion, String snapshotId, String commitSha, String provider, String model,
                           String status, int batchOffset, Integer nextBatchOffset, int totalBatches, List<FileScope> files, List<Finding> findings, int rejectedFindings,
                           int successfulBatches, int failedBatches, List<String> limitations, List<Retrieval> retrieval) {
    public ReviewReport { retrieval = retrieval == null ? List.of() : List.copyOf(retrieval); }
    public record Retrieval(int batchOffset, ContextBundle context) {}
    public record FileScope(String path, String language, String sha256, int lines, String status, String reason) {}
    public record Evidence(String path, int startLine, int endLine, String snippet) {}
    public record Finding(String kind, String category, String severity, String title, String explanation,
                          String guidance, List<Evidence> evidence, List<String> standardIds) {
        public Finding { standardIds = standardIds == null ? List.of() : List.copyOf(standardIds); }
        public Finding(String kind, String category, String severity, String title, String explanation, String guidance, List<Evidence> evidence) {
            this(kind, category, severity, title, explanation, guidance, evidence, List.of());
        }
    }
    public record BatchResponse(List<String> reviewedPaths, List<Finding> findings) {}
}

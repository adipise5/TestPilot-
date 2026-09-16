package com.testpilot.repository.dto;

import java.util.List;

/** Selection evidence only: excluded file contents are never retained. */
public record RepositorySelectionResponse(String commitSha, List<FileDecision> files, List<BuildContext> buildContexts) {
    public record FileDecision(String path, String language, String disposition, String reason) {}
    public record BuildContext(String path, String ecosystem, String testCommandHint) {}
}

package com.testpilot.rag.model;

public record RagScope(Long tenantId, Long projectId, String commitSha) {
    public RagScope {
        if (tenantId == null || projectId == null || commitSha == null || commitSha.isBlank()) {
            throw new IllegalArgumentException("RAG scope requires tenant, project, and immutable revision");
        }
    }

    public static RagScope global() {
        return new RagScope(0L, 0L, "global");
    }
}

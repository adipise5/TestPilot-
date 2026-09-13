package com.testpilot.rag.dto;

import java.util.List;

public record RagRetrievalResult(
        Long traceId,
        String queryHash,
        String context,
        int packedTokens,
        List<RagQueryResult> results,
        List<RagCitation> citations
) {
    public static RagRetrievalResult empty(String queryHash) {
        return new RagRetrievalResult(null, queryHash, "", 0, List.of(), List.of());
    }
}

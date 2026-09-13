package com.testpilot.rag.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.rag.entity.RagRetrievalTrace;

import java.time.LocalDateTime;
import java.util.List;

public record RagRetrievalTraceResponse(
        Long id,
        Long testRunId,
        Long projectId,
        String commitSha,
        String queryHash,
        String queryText,
        String packedContext,
        int packedTokens,
        String retrievalConfig,
        List<RagCitation> citations,
        LocalDateTime createdAt
) {
    public static RagRetrievalTraceResponse from(RagRetrievalTrace trace, ObjectMapper mapper) {
        try {
            List<RagCitation> citations = mapper.readValue(
                    trace.getCitationsJson(), new TypeReference<List<RagCitation>>() {});
            return new RagRetrievalTraceResponse(
                    trace.getId(), trace.getTestRunId(), trace.getProjectId(), trace.getCommitSha(),
                    trace.getQueryHash(), trace.getQueryText(), trace.getPackedContext(), trace.getPackedTokens(),
                    trace.getRetrievalConfig(), citations, trace.getCreatedAt());
        } catch (Exception e) {
            throw new IllegalStateException("Stored RAG citation trace could not be decoded", e);
        }
    }
}

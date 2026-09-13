package com.testpilot.rag.dto;

public record RagQueryResult(
        Long chunkId,
        Long documentId,
        String content,
        double similarityScore,
        double lexicalScore,
        double fusionScore,
        double rerankScore,
        String source,
        String symbol,
        String citationUri,
        int tokenCount
) {
    public RagQueryResult(Long chunkId, Long documentId, String content, double similarityScore) {
        this(chunkId, documentId, content, similarityScore, 0, 0, similarityScore,
                null, null, null, Math.max(1, (content.length() + 3) / 4));
    }
}

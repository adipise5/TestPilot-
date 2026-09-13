package com.testpilot.rag.dto;

public record RagCitation(
        Long chunkId,
        String chunkKey,
        String uri,
        String source,
        String symbol,
        int startLine,
        int endLine,
        String contentHash,
        double score
) {}

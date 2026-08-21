package com.testpilot.rag.dto;

public record RagQueryResult(
        Long chunkId,
        Long documentId,
        String content,
        double similarityScore
) {}

package com.testpilot.rag.dto;

public record RagIngestionResult(
        int documentsCreated,
        int documentsReused,
        int chunksCreated,
        String commitSha,
        String embeddingModel,
        String ingestionVersion
) {}

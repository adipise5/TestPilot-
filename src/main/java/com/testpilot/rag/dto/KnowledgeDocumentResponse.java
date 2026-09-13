package com.testpilot.rag.dto;

import com.testpilot.rag.entity.KnowledgeDocument;
import java.time.LocalDateTime;

public record KnowledgeDocumentResponse(
        Long id,
        Long projectId,
        String title,
        String source,
        String commitSha,
        String contentHash,
        String documentType,
        String embeddingModel,
        String ingestionVersion,
        String content,
        LocalDateTime createdAt
) {
    public static KnowledgeDocumentResponse fromEntity(KnowledgeDocument doc) {
        return new KnowledgeDocumentResponse(
                doc.getId(),
                doc.getProjectId(),
                doc.getTitle(),
                doc.getSource(),
                doc.getCommitSha(),
                doc.getContentHash(),
                doc.getDocumentType().name(),
                doc.getEmbeddingModel(),
                doc.getIngestionVersion(),
                doc.getContent(),
                doc.getCreatedAt()
        );
    }
}

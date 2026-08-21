package com.testpilot.rag.dto;

import com.testpilot.rag.entity.KnowledgeDocument;
import java.time.LocalDateTime;

public record KnowledgeDocumentResponse(
        Long id,
        String title,
        String source,
        String content,
        LocalDateTime createdAt
) {
    public static KnowledgeDocumentResponse fromEntity(KnowledgeDocument doc) {
        return new KnowledgeDocumentResponse(
                doc.getId(),
                doc.getTitle(),
                doc.getSource(),
                doc.getContent(),
                doc.getCreatedAt()
        );
    }
}

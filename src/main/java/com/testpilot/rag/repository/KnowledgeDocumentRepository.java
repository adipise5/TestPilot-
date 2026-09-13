package com.testpilot.rag.repository;

import com.testpilot.rag.entity.KnowledgeDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {
    List<KnowledgeDocument> findByTenantIdAndProjectIdOrderBySource(Long tenantId, Long projectId);

    Optional<KnowledgeDocument> findByTenantIdAndProjectIdAndCommitShaAndSourceAndContentHashAndEmbeddingModelAndIngestionVersion(
            Long tenantId,
            Long projectId,
            String commitSha,
            String source,
            String contentHash,
            String embeddingModel,
            String ingestionVersion);
}

package com.testpilot.rag.repository;

import com.testpilot.rag.entity.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {
    List<DocumentChunk> findByDocumentId(Long documentId);
    List<DocumentChunk> findByTenantIdAndProjectIdAndCommitShaAndEmbeddingModel(
            Long tenantId, Long projectId, String commitSha, String embeddingModel);
    Optional<DocumentChunk> findByChunkKey(String chunkKey);
}

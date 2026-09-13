package com.testpilot.rag.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "document_chunks",
        uniqueConstraints = @UniqueConstraint(name = "uk_rag_chunk_key", columnNames = "chunk_key"),
        indexes = {
                @Index(name = "idx_rag_chunk_scope", columnList = "tenant_id,project_id,commit_sha"),
                @Index(name = "idx_rag_chunk_document", columnList = "document_id,ordinal")
        })
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "commit_sha", nullable = false, length = 80)
    private String commitSha;

    @Column(name = "chunk_key", nullable = false, length = 64)
    private String chunkKey;

    @Column(nullable = false)
    private int ordinal;

    @Enumerated(EnumType.STRING)
    @Column(name = "chunk_kind", nullable = false, length = 30)
    private RagChunkKind chunkKind;

    @Column(length = 500)
    private String symbol;

    @Column(nullable = false, length = 700)
    private String source;

    @Column(name = "start_line", nullable = false)
    private int startLine;

    @Column(name = "end_line", nullable = false)
    private int endLine;

    @Column(name = "token_count", nullable = false)
    private int tokenCount;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "embedding_model", nullable = false, length = 120)
    private String embeddingModel;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "embedding_data", columnDefinition = "TEXT", nullable = false)
    private String embeddingData;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected DocumentChunk() {}

    public DocumentChunk(Long documentId, String content, float[] embedding) {
        this(documentId, 0L, 0L, "global", "legacy-" + Math.abs(content.hashCode()), 0,
                RagChunkKind.FALLBACK, null, "legacy", 1, 1,
                Math.max(1, (content.length() + 3) / 4), "legacy", "legacy", content, embedding);
    }

    public DocumentChunk(
            Long documentId,
            Long tenantId,
            Long projectId,
            String commitSha,
            String chunkKey,
            int ordinal,
            RagChunkKind chunkKind,
            String symbol,
            String source,
            int startLine,
            int endLine,
            int tokenCount,
            String contentHash,
            String embeddingModel,
            String content,
            float[] embedding) {
        this.documentId = documentId;
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.commitSha = commitSha;
        this.chunkKey = chunkKey;
        this.ordinal = ordinal;
        this.chunkKind = chunkKind;
        this.symbol = symbol;
        this.source = source;
        this.startLine = startLine;
        this.endLine = endLine;
        this.tokenCount = tokenCount;
        this.contentHash = contentHash;
        this.embeddingModel = embeddingModel;
        this.content = content;
        setEmbeddingVector(embedding);
    }

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); }

    public float[] getEmbeddingVector() {
        if (embeddingData == null || embeddingData.isEmpty()) return new float[0];
        String[] parts = embeddingData.split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) vector[i] = Float.parseFloat(parts[i]);
        return vector;
    }

    public void setEmbeddingVector(float[] vector) {
        if (vector == null || vector.length == 0) {
            embeddingData = "";
            return;
        }
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < vector.length; i++) {
            if (!Float.isFinite(vector[i])) throw new IllegalArgumentException("Embedding values must be finite");
            if (i > 0) value.append(',');
            value.append(vector[i]);
        }
        embeddingData = value.toString();
    }

    public Long getId() { return id; }
    public Long getDocumentId() { return documentId; }
    public Long getTenantId() { return tenantId; }
    public Long getProjectId() { return projectId; }
    public String getCommitSha() { return commitSha; }
    public String getChunkKey() { return chunkKey; }
    public int getOrdinal() { return ordinal; }
    public RagChunkKind getChunkKind() { return chunkKind; }
    public String getSymbol() { return symbol; }
    public String getSource() { return source; }
    public int getStartLine() { return startLine; }
    public int getEndLine() { return endLine; }
    public int getTokenCount() { return tokenCount; }
    public String getContentHash() { return contentHash; }
    public String getEmbeddingModel() { return embeddingModel; }
    public String getContent() { return content; }
    public String getEmbeddingData() { return embeddingData; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}

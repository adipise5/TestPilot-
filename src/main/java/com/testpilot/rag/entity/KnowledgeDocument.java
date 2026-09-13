package com.testpilot.rag.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "knowledge_documents",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_rag_document_version",
                columnNames = {"tenant_id", "project_id", "commit_sha", "source", "content_hash",
                        "embedding_model", "ingestion_version"}))
public class KnowledgeDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, length = 700)
    private String source;

    @Column(name = "commit_sha", nullable = false, length = 80)
    private String commitSha;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 30)
    private RagDocumentType documentType;

    @Column(name = "framework_version", nullable = false, length = 100)
    private String frameworkVersion;

    @Column(name = "embedding_model", nullable = false, length = 120)
    private String embeddingModel;

    @Column(name = "ingestion_version", nullable = false, length = 40)
    private String ingestionVersion;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected KnowledgeDocument() {}

    public KnowledgeDocument(String title, String source, String content) {
        this(0L, 0L, title, source, "global", "legacy", RagDocumentType.TESTING_GUIDE,
                "unspecified", "legacy", "rag-v1", content);
    }

    public KnowledgeDocument(
            Long tenantId,
            Long projectId,
            String title,
            String source,
            String commitSha,
            String contentHash,
            RagDocumentType documentType,
            String frameworkVersion,
            String embeddingModel,
            String ingestionVersion,
            String content) {
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.title = title;
        this.source = source;
        this.commitSha = commitSha;
        this.contentHash = contentHash;
        this.documentType = documentType;
        this.frameworkVersion = frameworkVersion;
        this.embeddingModel = embeddingModel;
        this.ingestionVersion = ingestionVersion;
        this.content = content;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getTenantId() { return tenantId; }
    public Long getProjectId() { return projectId; }
    public String getTitle() { return title; }
    public String getSource() { return source; }
    public String getCommitSha() { return commitSha; }
    public String getContentHash() { return contentHash; }
    public RagDocumentType getDocumentType() { return documentType; }
    public String getFrameworkVersion() { return frameworkVersion; }
    public String getEmbeddingModel() { return embeddingModel; }
    public String getIngestionVersion() { return ingestionVersion; }
    public String getContent() { return content; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}

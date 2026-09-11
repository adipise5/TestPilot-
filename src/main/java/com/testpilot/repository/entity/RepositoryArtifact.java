package com.testpilot.repository.entity;

import jakarta.persistence.*;

@Entity
@Table(
        name = "repository_artifacts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_repository_artifact_path",
                columnNames = {"ingestion_id", "path"}))
public class RepositoryArtifact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ingestion_id", nullable = false)
    private Long ingestionId;

    @Column(nullable = false, length = 512)
    private String path;

    @Column(name = "object_sha", length = 64)
    private String objectSha;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RepositoryArtifactKind kind;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    public RepositoryArtifact() {}

    public RepositoryArtifact(
            Long ingestionId,
            String path,
            String objectSha,
            String contentHash,
            RepositoryArtifactKind kind,
            long sizeBytes,
            String content) {
        this.ingestionId = ingestionId;
        this.path = path;
        this.objectSha = objectSha;
        this.contentHash = contentHash;
        this.kind = kind;
        this.sizeBytes = sizeBytes;
        this.content = content;
    }

    public Long getId() { return id; }
    public Long getIngestionId() { return ingestionId; }
    public String getPath() { return path; }
    public String getObjectSha() { return objectSha; }
    public String getContentHash() { return contentHash; }
    public RepositoryArtifactKind getKind() { return kind; }
    public long getSizeBytes() { return sizeBytes; }
    public String getContent() { return content; }
}

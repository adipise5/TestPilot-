package com.testpilot.repository.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "repository_ingestions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_repository_ingestion_revision",
                columnNames = {"connected_repository_id", "commit_sha"}))
public class RepositoryIngestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "connected_repository_id", nullable = false)
    private Long connectedRepositoryId;

    @Column(name = "commit_sha", nullable = false, length = 40)
    private String commitSha;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RepositoryIngestionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "build_system", nullable = false)
    private BuildSystem buildSystem;

    @Column(name = "file_count", nullable = false)
    private int fileCount;

    @Column(name = "catalog_hash", length = 64)
    private String catalogHash;

    @Column(name = "selection_report", columnDefinition = "TEXT")
    private String selectionReport;

    public String getSelectionReport() { return selectionReport; }
    public void setSelectionReport(String report) { this.selectionReport = report; }

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public RepositoryIngestion() {}

    public RepositoryIngestion(Long connectedRepositoryId, String commitSha) {
        this.connectedRepositoryId = connectedRepositoryId;
        this.commitSha = commitSha;
        this.status = RepositoryIngestionStatus.RUNNING;
        this.buildSystem = BuildSystem.UNKNOWN;
    }

    @PrePersist
    void onCreate() {
        startedAt = LocalDateTime.now();
    }

    public void complete(BuildSystem buildSystem, int fileCount, String catalogHash) {
        this.status = RepositoryIngestionStatus.COMPLETED;
        this.buildSystem = buildSystem;
        this.fileCount = fileCount;
        this.catalogHash = catalogHash;
        this.failureReason = null;
        this.completedAt = LocalDateTime.now();
    }

    public void retry() {
        this.status = RepositoryIngestionStatus.RUNNING;
        this.failureReason = null;
        this.completedAt = null;
    }

    public void fail(String reason) {
        this.status = RepositoryIngestionStatus.FAILED;
        this.failureReason = reason == null ? "Repository ingestion failed" : reason.substring(0, Math.min(500, reason.length()));
        this.completedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getConnectedRepositoryId() { return connectedRepositoryId; }
    public String getCommitSha() { return commitSha; }
    public RepositoryIngestionStatus getStatus() { return status; }
    public BuildSystem getBuildSystem() { return buildSystem; }
    public int getFileCount() { return fileCount; }
    public String getCatalogHash() { return catalogHash; }
    public String getFailureReason() { return failureReason; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
}

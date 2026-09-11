package com.testpilot.project.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "code_files",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_code_file_origin_path",
                columnNames = {"project_id", "origin", "file_path"}))
public class CodeFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CodeFileOrigin origin;

    @Column(name = "connected_repository_id")
    private Long connectedRepositoryId;

    @Column(name = "commit_sha", length = 40)
    private String commitSha;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public CodeFile() {}

    public CodeFile(Long projectId, String fileName, String filePath, String content) {
        this.projectId = projectId;
        this.fileName = fileName;
        this.filePath = filePath;
        this.content = content;
        this.origin = CodeFileOrigin.MANUAL;
    }

    public static CodeFile fromRepository(
            Long projectId,
            Long connectedRepositoryId,
            String commitSha,
            String fileName,
            String filePath,
            String content) {
        CodeFile file = new CodeFile(projectId, fileName, filePath, content);
        file.origin = CodeFileOrigin.REPOSITORY;
        file.connectedRepositoryId = connectedRepositoryId;
        file.commitSha = commitSha;
        return file;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public CodeFileOrigin getOrigin() { return origin; }
    public Long getConnectedRepositoryId() { return connectedRepositoryId; }
    public String getCommitSha() { return commitSha; }
}

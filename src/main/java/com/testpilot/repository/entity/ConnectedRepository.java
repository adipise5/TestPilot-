package com.testpilot.repository.entity;

import com.testpilot.repository.connector.RepositoryTransport;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "connected_repositories",
        uniqueConstraints = @UniqueConstraint(name = "uk_connected_repository_project", columnNames = "project_id"))
public class ConnectedRepository {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RepositoryProvider provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RepositoryTransport transport;

    @Column(nullable = false, length = 100)
    private String owner;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "default_branch", nullable = false, length = 255)
    private String defaultBranch;

    @Column(name = "selected_commit_sha", nullable = false, length = 40)
    private String selectedCommitSha;

    @Column(name = "installation_id")
    private Long installationId;

    @Column(name = "installation_scope", nullable = false, length = 300)
    private String installationScope;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RepositoryConnectionStatus status;

    @Column(name = "connected_at", nullable = false, updatable = false)
    private LocalDateTime connectedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public ConnectedRepository() {}

    public ConnectedRepository(
            Long projectId,
            RepositoryTransport transport,
            String owner,
            String name,
            String defaultBranch,
            String selectedCommitSha,
            Long installationId,
            String installationScope) {
        this.projectId = projectId;
        this.provider = RepositoryProvider.GITHUB;
        this.transport = transport;
        this.owner = owner;
        this.name = name;
        this.defaultBranch = defaultBranch;
        this.selectedCommitSha = selectedCommitSha;
        this.installationId = installationId;
        this.installationScope = installationScope;
        this.status = RepositoryConnectionStatus.CONNECTED;
    }

    @PrePersist
    void onCreate() {
        connectedAt = LocalDateTime.now();
        updatedAt = connectedAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getProjectId() { return projectId; }
    public RepositoryProvider getProvider() { return provider; }
    public RepositoryTransport getTransport() { return transport; }
    public String getOwner() { return owner; }
    public String getName() { return name; }
    public String getDefaultBranch() { return defaultBranch; }
    public String getSelectedCommitSha() { return selectedCommitSha; }
    public Long getInstallationId() { return installationId; }
    public String getInstallationScope() { return installationScope; }
    public RepositoryConnectionStatus getStatus() { return status; }
    public LocalDateTime getConnectedAt() { return connectedAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void selectCommit(String commitSha) {
        this.selectedCommitSha = commitSha;
    }

    public void disconnect() {
        this.status = RepositoryConnectionStatus.DISCONNECTED;
    }

    public void reconnect(
            RepositoryTransport transport,
            String owner,
            String name,
            String defaultBranch,
            String selectedCommitSha,
            Long installationId,
            String installationScope) {
        this.transport = transport;
        this.owner = owner;
        this.name = name;
        this.defaultBranch = defaultBranch;
        this.selectedCommitSha = selectedCommitSha;
        this.installationId = installationId;
        this.installationScope = installationScope;
        this.status = RepositoryConnectionStatus.CONNECTED;
    }
}

package com.testpilot.repository.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "github_installation_grants",
        uniqueConstraints = @UniqueConstraint(name = "uk_github_installation", columnNames = "installation_id"))
public class GitHubInstallationGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "installation_id", nullable = false)
    private Long installationId;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public GitHubInstallationGrant() {}

    public GitHubInstallationGrant(Long userId, Long installationId) {
        this.userId = userId;
        this.installationId = installationId;
        this.active = true;
    }

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); }

    public Long getUserId() { return userId; }
    public Long getInstallationId() { return installationId; }
    public boolean isActive() { return active; }
    public void activate() { active = true; }
    public void revoke() { active = false; }
}

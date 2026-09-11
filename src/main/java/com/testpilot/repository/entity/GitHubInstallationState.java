package com.testpilot.repository.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "github_installation_states")
public class GitHubInstallationState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "state_hash", nullable = false, unique = true, length = 64)
    private String stateHash;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private boolean used;

    public GitHubInstallationState() {}

    public GitHubInstallationState(String stateHash, Long userId, LocalDateTime expiresAt) {
        this.stateHash = stateHash;
        this.userId = userId;
        this.expiresAt = expiresAt;
    }

    public String getStateHash() { return stateHash; }
    public Long getUserId() { return userId; }
    public boolean canConsume() { return !used && expiresAt.isAfter(LocalDateTime.now()); }
    public void consume() { used = true; }
}

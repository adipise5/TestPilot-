package com.testpilot.repository.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "repository_audit_events")
public class RepositoryAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "connected_repository_id")
    private Long connectedRepositoryId;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "installation_id")
    private Long installationId;

    @Column(length = 100)
    private String owner;

    @Column(length = 100)
    private String repositoryName;

    @Column(nullable = false, length = 80)
    private String action;

    @Column(nullable = false, length = 40)
    private String outcome;

    @Column(length = 500)
    private String detail;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    public RepositoryAuditEvent() {}

    public RepositoryAuditEvent(
            Long connectedRepositoryId,
            Long actorUserId,
            Long installationId,
            String owner,
            String repositoryName,
            String action,
            String outcome,
            String detail) {
        this.connectedRepositoryId = connectedRepositoryId;
        this.actorUserId = actorUserId;
        this.installationId = installationId;
        this.owner = owner;
        this.repositoryName = repositoryName;
        this.action = action;
        this.outcome = outcome;
        this.detail = detail == null ? null : detail.substring(0, Math.min(500, detail.length()));
    }

    @PrePersist
    void onCreate() { occurredAt = LocalDateTime.now(); }
}

package com.testpilot.repository.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "github_webhook_deliveries")
public class GitHubWebhookDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "delivery_id", nullable = false, unique = true, length = 100)
    private String deliveryId;

    @Column(name = "event_name", nullable = false, length = 100)
    private String eventName;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Column(name = "received_at", nullable = false, updatable = false)
    private LocalDateTime receivedAt;

    public GitHubWebhookDelivery() {}

    public GitHubWebhookDelivery(String deliveryId, String eventName, String payloadHash) {
        this.deliveryId = deliveryId;
        this.eventName = eventName;
        this.payloadHash = payloadHash;
    }

    @PrePersist
    void onCreate() { receivedAt = LocalDateTime.now(); }
}

package com.testpilot.delivery.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "delivery_records",
        uniqueConstraints = @UniqueConstraint(name = "uk_delivery_test_run", columnNames = "test_run_id"),
        indexes = @Index(name = "idx_delivery_project_created", columnList = "project_id,created_at"))
public class DeliveryRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "test_run_id", nullable = false)
    private Long testRunId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "connected_repository_id", nullable = false)
    private Long connectedRepositoryId;

    @Column(name = "repository_owner", nullable = false, length = 100)
    private String repositoryOwner;

    @Column(name = "repository_name", nullable = false, length = 100)
    private String repositoryName;

    @Column(name = "installation_id", nullable = false)
    private Long installationId;

    @Column(name = "base_commit_sha", nullable = false, length = 40)
    private String baseCommitSha;

    @Column(name = "base_branch", nullable = false, length = 255)
    private String baseBranch;

    @Column(name = "delivery_branch", nullable = false, unique = true, length = 255)
    private String deliveryBranch;

    @Column(name = "patch_sha256", nullable = false, length = 64)
    private String patchSha256;

    @Column(name = "patch_text", nullable = false, columnDefinition = "TEXT")
    private String patchText;

    @Column(name = "changes_json", nullable = false, columnDefinition = "TEXT")
    private String changesJson;

    @Column(name = "pull_request_title", nullable = false, length = 256)
    private String pullRequestTitle;

    @Column(name = "pull_request_body", nullable = false, columnDefinition = "TEXT")
    private String pullRequestBody;

    @Column(nullable = false, length = 2000)
    private String limitations;

    @Column(name = "rollback_path", nullable = false, length = 1000)
    private String rollbackPath;

    @Column(name = "validation_result", nullable = false, length = 40)
    private String validationResult;

    @Column(name = "validation_summary", nullable = false, length = 2000)
    private String validationSummary;

    @Column(name = "validated_at", nullable = false)
    private LocalDateTime validatedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private DeliveryStatus status;

    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserId;

    @Column(name = "reviewed_by_user_id")
    private Long reviewedByUserId;

    @Column(name = "reviewer_name", length = 150)
    private String reviewerName;

    @Column(name = "reviewer_email", length = 255)
    private String reviewerEmail;

    @Column(name = "review_comment", length = 500)
    private String reviewComment;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "delivery_actor_user_id")
    private Long deliveryActorUserId;

    @Column(name = "delivery_attempts", nullable = false)
    private int deliveryAttempts;

    @Column(name = "pull_request_number")
    private Long pullRequestNumber;

    @Column(name = "pull_request_url", length = 1000)
    private String pullRequestUrl;

    @Column(name = "head_commit_sha", length = 40)
    private String headCommitSha;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    protected DeliveryRecord() {}

    public DeliveryRecord(
            Long testRunId,
            Long projectId,
            Long connectedRepositoryId,
            String repositoryOwner,
            String repositoryName,
            Long installationId,
            String baseCommitSha,
            String baseBranch,
            String deliveryBranch,
            String patchSha256,
            String patchText,
            String changesJson,
            String pullRequestTitle,
            String pullRequestBody,
            String limitations,
            String rollbackPath,
            String validationSummary,
            Long createdByUserId) {
        this.testRunId = testRunId;
        this.projectId = projectId;
        this.connectedRepositoryId = connectedRepositoryId;
        this.repositoryOwner = repositoryOwner;
        this.repositoryName = repositoryName;
        this.installationId = installationId;
        this.baseCommitSha = baseCommitSha;
        this.baseBranch = baseBranch;
        this.deliveryBranch = deliveryBranch;
        this.patchSha256 = patchSha256;
        this.patchText = patchText;
        this.changesJson = changesJson;
        this.pullRequestTitle = pullRequestTitle;
        this.pullRequestBody = pullRequestBody;
        this.limitations = limitations;
        this.rollbackPath = rollbackPath;
        this.validationResult = "PASSED";
        this.validationSummary = validationSummary;
        this.validatedAt = LocalDateTime.now();
        this.status = DeliveryStatus.AWAITING_APPROVAL;
        this.createdByUserId = createdByUserId;
    }

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public void recordDecision(
            boolean approved,
            Long reviewerId,
            String reviewerName,
            String reviewerEmail,
            String comment) {
        if (status != DeliveryStatus.AWAITING_APPROVAL) {
            throw new IllegalStateException("Delivery proposal is not awaiting approval");
        }
        status = approved ? DeliveryStatus.APPROVED : DeliveryStatus.REJECTED;
        reviewedByUserId = reviewerId;
        this.reviewerName = reviewerName;
        this.reviewerEmail = reviewerEmail;
        reviewComment = truncate(comment, 500);
        reviewedAt = LocalDateTime.now();
    }

    public void beginDelivery(Long actorUserId) {
        if (status != DeliveryStatus.APPROVED && status != DeliveryStatus.FAILED) {
            throw new IllegalStateException("Delivery requires an approved proposal");
        }
        status = DeliveryStatus.DELIVERING;
        deliveryActorUserId = actorUserId;
        deliveryAttempts++;
        failureReason = null;
    }

    public void delivered(Long pullRequestNumber, String pullRequestUrl, String headCommitSha) {
        if (status != DeliveryStatus.DELIVERING) {
            throw new IllegalStateException("Delivery is not in progress");
        }
        this.pullRequestNumber = pullRequestNumber;
        this.pullRequestUrl = pullRequestUrl;
        this.headCommitSha = headCommitSha;
        status = DeliveryStatus.DELIVERED;
        deliveredAt = LocalDateTime.now();
    }

    public void failed(String reason) {
        if (status != DeliveryStatus.DELIVERING) {
            throw new IllegalStateException("Delivery is not in progress");
        }
        status = DeliveryStatus.FAILED;
        failureReason = truncate(reason == null ? "GitHub delivery failed" : reason, 1000);
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.substring(0, Math.min(value.length(), max));
    }

    public Long getId() { return id; }
    public Long getTestRunId() { return testRunId; }
    public Long getProjectId() { return projectId; }
    public Long getConnectedRepositoryId() { return connectedRepositoryId; }
    public String getRepositoryOwner() { return repositoryOwner; }
    public String getRepositoryName() { return repositoryName; }
    public Long getInstallationId() { return installationId; }
    public String getBaseCommitSha() { return baseCommitSha; }
    public String getBaseBranch() { return baseBranch; }
    public String getDeliveryBranch() { return deliveryBranch; }
    public String getPatchSha256() { return patchSha256; }
    public String getPatchText() { return patchText; }
    public String getChangesJson() { return changesJson; }
    public String getPullRequestTitle() { return pullRequestTitle; }
    public String getPullRequestBody() { return pullRequestBody; }
    public String getLimitations() { return limitations; }
    public String getRollbackPath() { return rollbackPath; }
    public String getValidationResult() { return validationResult; }
    public String getValidationSummary() { return validationSummary; }
    public LocalDateTime getValidatedAt() { return validatedAt; }
    public DeliveryStatus getStatus() { return status; }
    public Long getCreatedByUserId() { return createdByUserId; }
    public Long getReviewedByUserId() { return reviewedByUserId; }
    public String getReviewerName() { return reviewerName; }
    public String getReviewerEmail() { return reviewerEmail; }
    public String getReviewComment() { return reviewComment; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public Long getDeliveryActorUserId() { return deliveryActorUserId; }
    public int getDeliveryAttempts() { return deliveryAttempts; }
    public Long getPullRequestNumber() { return pullRequestNumber; }
    public String getPullRequestUrl() { return pullRequestUrl; }
    public String getHeadCommitSha() { return headCommitSha; }
    public String getFailureReason() { return failureReason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getDeliveredAt() { return deliveredAt; }
}

package com.testpilot.delivery.dto;

import com.testpilot.delivery.entity.DeliveryRecord;
import com.testpilot.delivery.entity.DeliveryStatus;

import java.time.LocalDateTime;

public record DeliveryResponse(
        Long id,
        Long testRunId,
        Long projectId,
        DeliveryStatus status,
        String repositoryOwner,
        String repositoryName,
        String baseCommitSha,
        String baseBranch,
        String deliveryBranch,
        String patchSha256,
        String patchText,
        String pullRequestTitle,
        String pullRequestBody,
        String limitations,
        String validationResult,
        String validationSummary,
        LocalDateTime validatedAt,
        Long reviewedByUserId,
        String reviewerName,
        String reviewerEmail,
        String reviewComment,
        LocalDateTime reviewedAt,
        int deliveryAttempts,
        Long pullRequestNumber,
        String pullRequestUrl,
        String headCommitSha,
        String rollbackPath,
        String failureReason,
        LocalDateTime createdAt,
        LocalDateTime deliveredAt
) {
    public static DeliveryResponse fromEntity(DeliveryRecord record) {
        return new DeliveryResponse(
                record.getId(), record.getTestRunId(), record.getProjectId(), record.getStatus(),
                record.getRepositoryOwner(), record.getRepositoryName(),
                record.getBaseCommitSha(), record.getBaseBranch(), record.getDeliveryBranch(),
                record.getPatchSha256(), record.getPatchText(), record.getPullRequestTitle(),
                record.getPullRequestBody(), record.getLimitations(), record.getValidationResult(),
                record.getValidationSummary(), record.getValidatedAt(), record.getReviewedByUserId(),
                record.getReviewerName(), record.getReviewerEmail(), record.getReviewComment(),
                record.getReviewedAt(), record.getDeliveryAttempts(), record.getPullRequestNumber(),
                record.getPullRequestUrl(), record.getHeadCommitSha(), record.getRollbackPath(),
                record.getFailureReason(), record.getCreatedAt(), record.getDeliveredAt());
    }
}

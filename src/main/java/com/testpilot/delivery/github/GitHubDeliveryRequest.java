package com.testpilot.delivery.github;

import java.util.List;

public record GitHubDeliveryRequest(
        String owner,
        String repository,
        long installationId,
        String baseCommitSha,
        String baseBranch,
        String deliveryBranch,
        String commitMessage,
        String pullRequestTitle,
        String pullRequestBody,
        List<DeliveryChange> changes
) {}

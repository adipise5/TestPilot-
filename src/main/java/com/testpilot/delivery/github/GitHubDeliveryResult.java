package com.testpilot.delivery.github;

public record GitHubDeliveryResult(Long pullRequestNumber, String pullRequestUrl, String headCommitSha) {}

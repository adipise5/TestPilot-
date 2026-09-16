package com.testpilot.delivery.github;

public interface GitHubDeliveryGateway {
    GitHubDeliveryResult deliver(GitHubDeliveryRequest request);
}

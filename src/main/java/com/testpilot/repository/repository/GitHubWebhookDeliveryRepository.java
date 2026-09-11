package com.testpilot.repository.repository;

import com.testpilot.repository.entity.GitHubWebhookDelivery;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GitHubWebhookDeliveryRepository extends JpaRepository<GitHubWebhookDelivery, Long> {
    boolean existsByDeliveryId(String deliveryId);
}

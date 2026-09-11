package com.testpilot.repository.controller;

import com.testpilot.repository.dto.GitHubWebhookResponse;
import com.testpilot.repository.service.GitHubWebhookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/integrations/github/webhooks")
public class GitHubWebhookController {

    private final GitHubWebhookService webhookService;

    public GitHubWebhookController(GitHubWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping
    public ResponseEntity<GitHubWebhookResponse> receive(
            @RequestHeader("X-GitHub-Delivery") String deliveryId,
            @RequestHeader("X-GitHub-Event") String eventName,
            @RequestHeader("X-Hub-Signature-256") String signature,
            @RequestBody byte[] payload) {
        return ResponseEntity.ok(webhookService.handle(deliveryId, eventName, signature, payload));
    }
}

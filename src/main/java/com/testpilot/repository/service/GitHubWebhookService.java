package com.testpilot.repository.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.common.exception.ExternalServiceException;
import com.testpilot.common.exception.InvalidRequestException;
import com.testpilot.repository.dto.GitHubWebhookResponse;
import com.testpilot.repository.entity.ConnectedRepository;
import com.testpilot.repository.entity.GitHubInstallationGrant;
import com.testpilot.repository.entity.GitHubWebhookDelivery;
import com.testpilot.repository.entity.RepositoryConnectionStatus;
import com.testpilot.repository.repository.ConnectedRepositoryRepository;
import com.testpilot.repository.repository.GitHubInstallationGrantRepository;
import com.testpilot.repository.repository.GitHubWebhookDeliveryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class GitHubWebhookService {

    private static final int MAX_WEBHOOK_BYTES = 1024 * 1024;

    private final ObjectMapper objectMapper;
    private final GitHubWebhookDeliveryRepository deliveryRepository;
    private final GitHubInstallationGrantRepository grantRepository;
    private final ConnectedRepositoryRepository connectedRepositoryRepository;
    private final RepositoryAuditService auditService;
    private final String webhookSecret;

    public GitHubWebhookService(
            ObjectMapper objectMapper,
            GitHubWebhookDeliveryRepository deliveryRepository,
            GitHubInstallationGrantRepository grantRepository,
            ConnectedRepositoryRepository connectedRepositoryRepository,
            RepositoryAuditService auditService,
            @Value("${testpilot.github.webhook-secret:}") String webhookSecret) {
        this.objectMapper = objectMapper;
        this.deliveryRepository = deliveryRepository;
        this.grantRepository = grantRepository;
        this.connectedRepositoryRepository = connectedRepositoryRepository;
        this.auditService = auditService;
        this.webhookSecret = webhookSecret;
    }

    @Transactional
    public GitHubWebhookResponse handle(String deliveryId, String eventName, String signature, byte[] payload) {
        if (payload.length > MAX_WEBHOOK_BYTES) {
            throw new InvalidRequestException("GitHub webhook payload exceeds 1 MiB");
        }
        if (deliveryId == null || deliveryId.isBlank() || deliveryId.length() > 100
                || eventName == null || eventName.isBlank() || eventName.length() > 100) {
            throw new InvalidRequestException("GitHub webhook headers are invalid");
        }
        verifySignature(signature, payload);
        if (deliveryRepository.existsByDeliveryId(deliveryId)) {
            return new GitHubWebhookResponse("DUPLICATE_IGNORED");
        }
        deliveryRepository.save(new GitHubWebhookDelivery(deliveryId, eventName, sha256(payload)));

        try {
            JsonNode body = objectMapper.readTree(payload);
            long installationId = body.path("installation").path("id").asLong(0);
            String action = body.path("action").asText();
            if (installationId > 0 && eventName.equals("installation")
                    && (action.equals("deleted") || action.equals("suspend"))) {
                revokeInstallation(installationId, "GitHub installation " + action);
            } else if (installationId > 0 && eventName.equals("installation_repositories")) {
                disconnectRemovedRepositories(installationId, body.path("repositories_removed"));
            } else if (installationId > 0 && eventName.equals("push")) {
                auditPush(installationId, body);
            }
            return new GitHubWebhookResponse("ACCEPTED");
        } catch (InvalidRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidRequestException("GitHub webhook payload is malformed");
        }
    }

    private void revokeInstallation(long installationId, String detail) {
        for (GitHubInstallationGrant grant : grantRepository.findByInstallationIdAndActiveTrue(installationId)) {
            grant.revoke();
            grantRepository.save(grant);
        }
        for (ConnectedRepository repository : connectedRepositoryRepository
                .findByInstallationIdAndStatus(installationId, RepositoryConnectionStatus.CONNECTED)) {
            repository.disconnect();
            connectedRepositoryRepository.save(repository);
            auditService.record(
                    repository.getId(), null, installationId, repository.getOwner(), repository.getName(),
                    "GITHUB_WEBHOOK", "DISCONNECTED", detail);
        }
    }

    private void disconnectRemovedRepositories(long installationId, JsonNode removedRepositories) {
        for (JsonNode removed : removedRepositories) {
            String fullName = removed.path("full_name").asText();
            String[] parts = fullName.split("/", 2);
            if (parts.length != 2) continue;
            connectedRepositoryRepository
                    .findByInstallationIdAndOwnerIgnoreCaseAndNameIgnoreCase(installationId, parts[0], parts[1])
                    .ifPresent(repository -> {
                        repository.disconnect();
                        connectedRepositoryRepository.save(repository);
                        auditService.record(
                                repository.getId(), null, installationId, repository.getOwner(), repository.getName(),
                                "GITHUB_WEBHOOK", "DISCONNECTED", "Repository removed from installation scope");
                    });
        }
    }

    private void auditPush(long installationId, JsonNode body) {
        String fullName = body.path("repository").path("full_name").asText();
        String[] parts = fullName.split("/", 2);
        if (parts.length != 2) return;
        connectedRepositoryRepository
                .findByInstallationIdAndOwnerIgnoreCaseAndNameIgnoreCase(installationId, parts[0], parts[1])
                .ifPresent(repository -> auditService.record(
                        repository.getId(), null, installationId, repository.getOwner(), repository.getName(),
                        "GITHUB_PUSH_OBSERVED", "SUCCESS", "after=" + body.path("after").asText()));
    }

    private void verifySignature(String signature, byte[] payload) {
        if (webhookSecret.isBlank()) {
            throw new ExternalServiceException("GitHub webhook verification is not configured");
        }
        if (signature == null || !signature.startsWith("sha256=")) {
            throw new AccessDeniedException("GitHub webhook signature is invalid");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(payload);
            byte[] supplied = HexFormat.of().parseHex(signature.substring("sha256=".length()));
            if (!MessageDigest.isEqual(expected, supplied)) {
                throw new AccessDeniedException("GitHub webhook signature is invalid");
            }
        } catch (AccessDeniedException e) {
            throw e;
        } catch (Exception e) {
            throw new AccessDeniedException("GitHub webhook signature is invalid");
        }
    }

    private String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}

package com.testpilot.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.repository.entity.GitHubWebhookDelivery;
import com.testpilot.repository.repository.ConnectedRepositoryRepository;
import com.testpilot.repository.repository.GitHubInstallationGrantRepository;
import com.testpilot.repository.repository.GitHubWebhookDeliveryRepository;
import com.testpilot.repository.service.GitHubWebhookService;
import com.testpilot.repository.service.RepositoryAuditService;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GitHubWebhookServiceTest {

    @Test
    void shouldVerifySignatureAndDeduplicateDelivery() throws Exception {
        GitHubWebhookDeliveryRepository deliveries = mock(GitHubWebhookDeliveryRepository.class);
        GitHubWebhookService service = service(deliveries);
        byte[] payload = "{\"zen\":\"keep it logically awesome\"}".getBytes(StandardCharsets.UTF_8);
        String signature = signature("webhook-secret", payload);

        when(deliveries.existsByDeliveryId("delivery-1")).thenReturn(false, true);
        assertEquals("ACCEPTED", service.handle("delivery-1", "ping", signature, payload).status());
        assertEquals("DUPLICATE_IGNORED", service.handle("delivery-1", "ping", signature, payload).status());
        verify(deliveries, times(1)).save(any(GitHubWebhookDelivery.class));
    }

    @Test
    void shouldRejectInvalidWebhookSignature() {
        GitHubWebhookService service = service(mock(GitHubWebhookDeliveryRepository.class));
        assertThrows(AccessDeniedException.class, () ->
                service.handle("delivery-2", "ping", "sha256=00", "{}".getBytes(StandardCharsets.UTF_8)));
    }

    private GitHubWebhookService service(GitHubWebhookDeliveryRepository deliveries) {
        return new GitHubWebhookService(
                new ObjectMapper(),
                deliveries,
                mock(GitHubInstallationGrantRepository.class),
                mock(ConnectedRepositoryRepository.class),
                mock(RepositoryAuditService.class),
                "webhook-secret");
    }

    private String signature(String secret, byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload));
    }
}

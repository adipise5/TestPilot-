package com.testpilot.repository.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.common.exception.ExternalServiceException;
import com.testpilot.common.exception.InvalidRequestException;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class GitHubAppTokenProvider {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String apiBase;
    private final String appId;
    private final String privateKeyValue;
    private final Map<TokenKey, CachedToken> cache = new ConcurrentHashMap<>();

    public GitHubAppTokenProvider(
            ObjectMapper objectMapper,
            @Value("${testpilot.github.api-base:https://api.github.com}") String apiBase,
            @Value("${testpilot.github.app-id:}") String appId,
            @Value("${testpilot.github.private-key:}") String privateKeyValue) {
        this.objectMapper = objectMapper;
        this.apiBase = apiBase.replaceAll("/$", "");
        this.appId = appId;
        this.privateKeyValue = privateKeyValue;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public String getInstallationToken(long installationId, Optional<String> repositoryName) {
        return getInstallationToken(installationId, repositoryName, TokenPermission.READ);
    }

    public String getDeliveryToken(long installationId, String repositoryName) {
        if (repositoryName == null || repositoryName.isBlank()) {
            throw new InvalidRequestException("A repository name is required for a delivery token");
        }
        return getInstallationToken(installationId, Optional.of(repositoryName), TokenPermission.DELIVERY);
    }

    private String getInstallationToken(
            long installationId,
            Optional<String> repositoryName,
            TokenPermission permission) {
        if (installationId <= 0) {
            throw new InvalidRequestException("A valid GitHub App installation ID is required");
        }
        TokenKey key = new TokenKey(installationId, repositoryName.orElse("*"), permission);
        CachedToken cached = cache.get(key);
        if (cached != null && cached.usable()) {
            return cached.value();
        }

        CachedToken created = createInstallationToken(installationId, repositoryName, permission);
        cache.put(key, created);
        return created.value();
    }

    private CachedToken createInstallationToken(
            long installationId,
            Optional<String> repositoryName,
            TokenPermission permission) {
        requireConfiguration();
        try {
            Instant now = Instant.now();
            String appJwt = Jwts.builder()
                    .issuer(appId)
                    .issuedAt(Date.from(now.minusSeconds(60)))
                    .expiration(Date.from(now.plusSeconds(9 * 60)))
                    .signWith(parsePrivateKey())
                    .compact();

            Map<String, String> permissions = permission == TokenPermission.DELIVERY
                    ? Map.of("contents", "write", "pull_requests", "write")
                    : Map.of("contents", "read");
            Map<String, Object> body = repositoryName
                    .<Map<String, Object>>map(name -> Map.of(
                            "repositories", new String[]{name},
                            "permissions", permissions))
                    .orElseGet(() -> Map.of("permissions", permissions));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/app/installations/" + installationId + "/access_tokens"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/vnd.github+json")
                    .header("Authorization", "Bearer " + appJwt)
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403 || response.statusCode() == 404
                    || response.statusCode() == 422) {
                throw new AccessDeniedException("Repository is outside the authorized GitHub installation scope");
            }
            if (response.statusCode() / 100 != 2) {
                throw new ExternalServiceException("GitHub could not issue an installation access token");
            }

            JsonNode json = objectMapper.readTree(response.body());
            String token = json.path("token").asText();
            Instant expiresAt = OffsetDateTime.parse(json.path("expires_at").asText()).toInstant();
            if (token.isBlank()) {
                throw new ExternalServiceException("GitHub returned an invalid installation token response");
            }
            return new CachedToken(token, expiresAt);
        } catch (AccessDeniedException | ExternalServiceException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExternalServiceException("GitHub token request was interrupted", e);
        } catch (Exception e) {
            throw new ExternalServiceException("GitHub App authentication failed", e);
        }
    }

    private PrivateKey parsePrivateKey() throws Exception {
        String pem = privateKeyValue.replace("\\n", "\n")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(pem);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decoded));
    }

    private void requireConfiguration() {
        if (appId.isBlank() || privateKeyValue.isBlank()) {
            throw new InvalidRequestException(
                    "GitHub App REST access is not configured; set GITHUB_APP_ID and GITHUB_APP_PRIVATE_KEY");
        }
    }

    private enum TokenPermission { READ, DELIVERY }

    private record TokenKey(long installationId, String repositoryName, TokenPermission permission) {}

    private record CachedToken(String value, Instant expiresAt) {
        private boolean usable() {
            return expiresAt.isAfter(Instant.now().plusSeconds(60));
        }
    }
}

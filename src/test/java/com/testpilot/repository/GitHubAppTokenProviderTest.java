package com.testpilot.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.testpilot.repository.github.GitHubAppTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class GitHubAppTokenProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> requestBodies = new ArrayList<>();
    private HttpServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/app/installations/42/access_tokens", this::issueToken);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void separatesRepositoryReadAndDeliveryWriteTokens() throws Exception {
        GitHubAppTokenProvider provider = new GitHubAppTokenProvider(
                objectMapper,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "12345",
                privateKeyPem());

        assertEquals("token-1", provider.getInstallationToken(42L, Optional.of("sample")));
        assertEquals("token-2", provider.getDeliveryToken(42L, "sample"));
        assertEquals("token-1", provider.getInstallationToken(42L, Optional.of("sample")));
        assertEquals("token-2", provider.getDeliveryToken(42L, "sample"));
        assertEquals(2, requestBodies.size(), "read and write scopes must use separate cache entries");

        JsonNode read = objectMapper.readTree(requestBodies.get(0));
        assertEquals("sample", read.path("repositories").get(0).asText());
        assertEquals("read", read.path("permissions").path("contents").asText());
        assertFalse(read.path("permissions").has("pull_requests"));

        JsonNode delivery = objectMapper.readTree(requestBodies.get(1));
        assertEquals("sample", delivery.path("repositories").get(0).asText());
        assertEquals("write", delivery.path("permissions").path("contents").asText());
        assertEquals("write", delivery.path("permissions").path("pull_requests").asText());
    }

    private void issueToken(HttpExchange exchange) throws IOException {
        assertEquals("POST", exchange.getRequestMethod());
        assertTrue(exchange.getRequestHeaders().getFirst("Authorization").startsWith("Bearer "));
        requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String response = "{\"token\":\"token-" + requestBodies.size() + "\",\"expires_at\":\""
                + OffsetDateTime.now(ZoneOffset.UTC).plusHours(1) + "\"}";
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(201, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String privateKeyPem() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        byte[] encoded = generator.generateKeyPair().getPrivate().getEncoded();
        String body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded);
        return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----";
    }
}

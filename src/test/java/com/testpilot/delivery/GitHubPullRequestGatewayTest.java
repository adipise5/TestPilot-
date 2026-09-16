package com.testpilot.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.testpilot.common.exception.ExternalServiceException;
import com.testpilot.common.validation.RepositoryPathPolicy;
import com.testpilot.delivery.github.*;
import com.testpilot.delivery.service.GitBranchPolicy;
import com.testpilot.repository.github.GitHubAppTokenProvider;
import com.testpilot.repository.github.GitHubCoordinatesPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GitHubPullRequestGatewayTest {

    private static final String BASE = "1111111111111111111111111111111111111111";
    private static final String BASE_TREE = "2222222222222222222222222222222222222222";
    private static final String NEW_TREE = "3333333333333333333333333333333333333333";
    private static final String HEAD = "4444444444444444444444444444444444444444";

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<RequestEvidence> requests = new ArrayList<>();
    private HttpServer server;
    private GitHubPullRequestGateway gateway;
    private boolean failPullRequest;
    private String pullRequestUrl = "https://github.example/octocat/sample/pull/91";

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/octocat/sample", this::handle);
        server.start();
        GitHubAppTokenProvider tokens = mock(GitHubAppTokenProvider.class);
        when(tokens.getDeliveryToken(42L, "sample")).thenReturn("delivery-token");
        gateway = new GitHubPullRequestGateway(
                mapper,
                tokens,
                new GitHubCoordinatesPolicy(),
                new RepositoryPathPolicy(),
                new GitBranchPolicy(),
                "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void createsTreeCommitDedicatedBranchAndPullRequest() throws Exception {
        GitHubDeliveryResult result = gateway.deliver(new GitHubDeliveryRequest(
                "octocat", "sample", 42L, BASE, "main", "testpilot/run-7-deadbeef",
                "Add generated tests", "TestPilot tests", "evidence body",
                List.of(new DeliveryChange(
                        "src/test/java/example/AppTest.java",
                        "package example; class AppTest {}\n"))));

        assertEquals(91L, result.pullRequestNumber());
        assertEquals("https://github.example/octocat/sample/pull/91", result.pullRequestUrl());
        assertEquals(HEAD, result.headCommitSha());
        assertEquals(List.of(
                "GET /repos/octocat/sample/git/commits/" + BASE,
                "POST /repos/octocat/sample/git/trees",
                "POST /repos/octocat/sample/git/commits",
                "POST /repos/octocat/sample/git/refs",
                "POST /repos/octocat/sample/pulls"),
                requests.stream().map(item -> item.method() + " " + item.path()).toList());

        JsonNode ref = bodyFor("/git/refs");
        assertEquals("refs/heads/testpilot/run-7-deadbeef", ref.path("ref").asText());
        assertEquals(HEAD, ref.path("sha").asText());
        JsonNode pull = bodyFor("/pulls");
        assertEquals("main", pull.path("base").asText());
        assertEquals("testpilot/run-7-deadbeef", pull.path("head").asText());
        assertNotEquals(pull.path("base").asText(), pull.path("head").asText());
    }

    @Test
    void removesItsDedicatedBranchWhenPullRequestCreationFails() {
        failPullRequest = true;

        assertThrows(ExternalServiceException.class, () -> gateway.deliver(new GitHubDeliveryRequest(
                "octocat", "sample", 42L, BASE, "main", "testpilot/run-8-feedface",
                "Add generated tests", "TestPilot tests", "evidence body",
                List.of(new DeliveryChange(
                        "src/test/java/example/AppTest.java",
                        "package example; class AppTest {}\n")))));

        assertTrue(requests.stream().anyMatch(item ->
                item.method().equals("DELETE")
                        && item.path().endsWith("/git/refs/heads/testpilot/run-8-feedface")));
    }

    @Test
    void rejectsUnsafePullRequestUrlAndRemovesItsDedicatedBranch() {
        pullRequestUrl = "javascript:alert(1)";

        assertThrows(ExternalServiceException.class, () -> gateway.deliver(new GitHubDeliveryRequest(
                "octocat", "sample", 42L, BASE, "main", "testpilot/run-9-cafebabe",
                "Add generated tests", "TestPilot tests", "evidence body",
                List.of(new DeliveryChange(
                        "src/test/java/example/AppTest.java",
                        "package example; class AppTest {}\n")))));

        assertTrue(requests.stream().anyMatch(item ->
                item.method().equals("DELETE")
                        && item.path().endsWith("/git/refs/heads/testpilot/run-9-cafebabe")));
    }

    private JsonNode bodyFor(String suffix) throws Exception {
        return requests.stream()
                .filter(item -> item.path().endsWith(suffix))
                .findFirst()
                .map(RequestEvidence::body)
                .map(value -> {
                    try {
                        return mapper.readTree(value);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .orElseThrow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        assertEquals("Bearer delivery-token", exchange.getRequestHeaders().getFirst("Authorization"));
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new RequestEvidence(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), body));
        String path = exchange.getRequestURI().getPath();
        if (exchange.getRequestMethod().equals("DELETE") && path.contains("/git/refs/heads/testpilot/")) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        String response;
        if (path.endsWith("/git/commits/" + BASE)) {
            response = "{\"sha\":\"" + BASE + "\",\"tree\":{\"sha\":\"" + BASE_TREE + "\"}}";
        } else if (path.endsWith("/git/trees")) {
            response = "{\"sha\":\"" + NEW_TREE + "\"}";
        } else if (path.endsWith("/git/commits")) {
            response = "{\"sha\":\"" + HEAD + "\"}";
        } else if (path.endsWith("/git/refs")) {
            response = "{\"ref\":\"refs/heads/testpilot/run-7-deadbeef\"}";
        } else if (path.endsWith("/pulls")) {
            if (failPullRequest) {
                byte[] bytes = "{\"message\":\"temporary failure\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
                return;
            }
            response = "{\"number\":91,\"html_url\":\"" + pullRequestUrl + "\"}";
        } else {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(201, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record RequestEvidence(String method, String path, String body) {}
}

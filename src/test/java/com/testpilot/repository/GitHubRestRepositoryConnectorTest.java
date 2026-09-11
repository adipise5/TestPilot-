package com.testpilot.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.testpilot.repository.connector.*;
import com.testpilot.repository.github.GitHubAppTokenProvider;
import com.testpilot.repository.github.GitHubCoordinatesPolicy;
import com.testpilot.repository.github.GitHubRestRepositoryConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GitHubRestRepositoryConnectorTest {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    private HttpServer server;
    private GitHubRestRepositoryConnector connector;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/octocat/sample", this::handleRepositoryRequest);
        server.createContext("/installation/repositories", exchange -> respond(exchange, """
                {"repositories":[{"name":"sample","owner":{"login":"octocat"},"default_branch":"main","visibility":"private"}]}
                """));
        server.start();

        GitHubAppTokenProvider tokenProvider = mock(GitHubAppTokenProvider.class);
        when(tokenProvider.getInstallationToken(eq(42L), any())).thenReturn("installation-token");
        connector = new GitHubRestRepositoryConnector(
                new ObjectMapper(),
                tokenProvider,
                new GitHubCoordinatesPolicy(),
                "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void shouldReadMetadataTreeAndFileOnlyAtResolvedCommit() {
        RepositoryCoordinates coordinates = new RepositoryCoordinates("octocat", "sample");
        RepositoryAccessContext access = new RepositoryAccessContext(42L, 7L);

        assertEquals("main", connector.getRepository(coordinates, access).defaultBranch());
        assertEquals(SHA, connector.resolveRevision(coordinates, "main", access));
        RepositoryTreeEntry entry = connector.listTree(coordinates, SHA, access).get(0);
        assertEquals("src/main/java/App.java", entry.path());
        assertEquals(
                "public class App {}",
                new String(connector.readFile(coordinates, SHA, entry, access).content(), StandardCharsets.UTF_8));
        assertEquals(1, connector.discoverRepositories(access).size());
    }

    private void handleRepositoryRequest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/commits/main")) {
            respond(exchange, "{\"sha\":\"" + SHA + "\"}");
        } else if (path.endsWith("/git/trees/" + SHA)) {
            respond(exchange, """
                    {"truncated":false,"tree":[{"path":"src/main/java/App.java","sha":"blob-sha","size":19,"type":"blob"}]}
                    """);
        } else if (path.endsWith("/contents/src/main/java/App.java")) {
            String encoded = Base64.getEncoder().encodeToString(
                    "public class App {}".getBytes(StandardCharsets.UTF_8));
            respond(exchange, "{\"encoding\":\"base64\",\"content\":\"" + encoded + "\"}");
        } else {
            respond(exchange, """
                    {"name":"sample","owner":{"login":"octocat"},"default_branch":"main","visibility":"private"}
                    """);
        }
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        assertEquals("Bearer installation-token", exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}

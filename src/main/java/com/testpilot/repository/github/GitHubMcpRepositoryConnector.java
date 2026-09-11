package com.testpilot.repository.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.common.exception.ExternalServiceException;
import com.testpilot.common.exception.InvalidRequestException;
import com.testpilot.common.validation.RepositoryPathPolicy;
import com.testpilot.repository.connector.*;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

@Component
public class GitHubMcpRepositoryConnector implements RepositoryConnector {

    private static final int MAX_FILE_BYTES = 512 * 1024;

    private final ObjectMapper objectMapper;
    private final GitHubCoordinatesPolicy coordinatesPolicy;
    private final RepositoryPathPolicy pathPolicy;
    private final String endpoint;
    private final String token;
    private final Set<String> allowedRepositories;

    public GitHubMcpRepositoryConnector(
            ObjectMapper objectMapper,
            GitHubCoordinatesPolicy coordinatesPolicy,
            RepositoryPathPolicy pathPolicy,
            @Value("${testpilot.github.mcp.endpoint:https://api.githubcopilot.com/mcp/}") String endpoint,
            @Value("${testpilot.github.mcp.token:}") String token,
            @Value("${testpilot.github.mcp.allowed-repositories:}") String allowedRepositories) {
        this.objectMapper = objectMapper;
        this.coordinatesPolicy = coordinatesPolicy;
        this.pathPolicy = pathPolicy;
        this.endpoint = endpoint;
        this.token = token;
        this.allowedRepositories = parseAllowlist(allowedRepositories);
    }

    @Override
    public RepositoryTransport transport() {
        return RepositoryTransport.GITHUB_MCP;
    }

    @Override
    public List<RemoteRepositoryMetadata> discoverRepositories(RepositoryAccessContext accessContext) {
        requireConfigured();
        List<RemoteRepositoryMetadata> repositories = new ArrayList<>();
        for (String allowed : allowedRepositories) {
            String[] parts = allowed.split("/", 2);
            repositories.add(getRepository(new RepositoryCoordinates(parts[0], parts[1]), accessContext));
        }
        return List.copyOf(repositories);
    }

    @Override
    public RemoteRepositoryMetadata getRepository(
            RepositoryCoordinates coordinates,
            RepositoryAccessContext accessContext) {
        RepositoryCoordinates safe = requireAllowed(coordinates);
        JsonNode result = callTool("search_repositories", Map.of(
                "query", "repo:" + safe.owner() + "/" + safe.name(),
                "perPage", 10,
                "minimal_output", true));
        JsonNode repository = findRepository(result, safe);
        if (repository == null) {
            throw new AccessDeniedException("Repository is outside the configured MCP scope");
        }
        String defaultBranch = firstText(repository, "default_branch", "defaultBranch");
        return new RemoteRepositoryMetadata(
                safe.owner(),
                safe.name(),
                defaultBranch.isBlank() ? "main" : defaultBranch,
                firstText(repository, "visibility"));
    }

    @Override
    public String resolveRevision(
            RepositoryCoordinates coordinates,
            String revision,
            RepositoryAccessContext accessContext) {
        RepositoryCoordinates safe = requireAllowed(coordinates);
        if (revision == null || revision.isBlank() || revision.length() > 255) {
            throw new InvalidRequestException("A branch, tag, or commit revision is required");
        }
        JsonNode result = callTool("get_commit", Map.of(
                "owner", safe.owner(),
                "repo", safe.name(),
                "sha", revision,
                "detail", "none"));
        return coordinatesPolicy.requireCommitSha(findFirstField(result, "sha"));
    }

    @Override
    public List<RepositoryTreeEntry> listTree(
            RepositoryCoordinates coordinates,
            String commitSha,
            RepositoryAccessContext accessContext) {
        RepositoryCoordinates safe = requireAllowed(coordinates);
        String safeSha = coordinatesPolicy.requireCommitSha(commitSha);
        JsonNode result = callTool("get_repository_tree", Map.of(
                "owner", safe.owner(),
                "repo", safe.name(),
                "tree_sha", safeSha,
                "recursive", true));

        List<RepositoryTreeEntry> entries = new ArrayList<>();
        collectTreeEntries(result, entries);
        return entries.stream().distinct().toList();
    }

    @Override
    public RepositoryFileContent readFile(
            RepositoryCoordinates coordinates,
            String commitSha,
            RepositoryTreeEntry entry,
            RepositoryAccessContext accessContext) {
        RepositoryCoordinates safe = requireAllowed(coordinates);
        String safeSha = coordinatesPolicy.requireCommitSha(commitSha);
        String path = pathPolicy.validateRepositoryPath(entry.path());
        if (entry.size() > MAX_FILE_BYTES) {
            throw new InvalidRequestException("Repository file exceeds the 512 KiB ingestion limit: " + path);
        }
        JsonNode result = callTool("get_file_contents", Map.of(
                "owner", safe.owner(),
                "repo", safe.name(),
                "path", path,
                "sha", safeSha));
        JsonNode contentNode = findObjectWithField(result, "content");
        if (contentNode == null) {
            throw new ExternalServiceException("GitHub MCP returned no content for " + path);
        }
        String value = contentNode.path("content").asText();
        byte[] content;
        try {
            content = "base64".equalsIgnoreCase(contentNode.path("encoding").asText())
                    ? Base64.getMimeDecoder().decode(value)
                    : value.getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ExternalServiceException("GitHub MCP returned invalid file content", e);
        }
        if (content.length > MAX_FILE_BYTES) {
            throw new InvalidRequestException("Repository file exceeds the 512 KiB ingestion limit: " + path);
        }
        return new RepositoryFileContent(path, entry.objectSha(), content);
    }

    private JsonNode callTool(String toolName, Map<String, Object> arguments) {
        requireConfigured();
        return withClient(client -> {
            McpSchema.CallToolResult result = client.callTool(
                    McpSchema.CallToolRequest.builder(toolName).arguments(arguments).build());
            if (Boolean.TRUE.equals(result.isError())) {
                throw new ExternalServiceException("GitHub MCP tool failed: " + toolName);
            }
            if (result.structuredContent() != null) {
                return objectMapper.valueToTree(result.structuredContent());
            }
            StringBuilder text = new StringBuilder();
            for (McpSchema.Content content : result.content()) {
                if (content instanceof McpSchema.TextContent textContent) {
                    text.append(textContent.text());
                }
            }
            return parseToolText(text.toString());
        });
    }

    private <T> T withClient(Function<McpSyncClient, T> action) {
        URI uri = URI.create(endpoint);
        String baseUri = uri.getScheme() + "://" + uri.getAuthority();
        String endpointPath = uri.getRawPath().isBlank() ? "/mcp/" : uri.getRawPath();
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(baseUri)
                .endpoint(endpointPath)
                .requestBuilder(HttpRequest.newBuilder()
                        .header("Authorization", "Bearer " + token)
                        .header("X-MCP-Toolsets", "repos"))
                .build();
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(30))
                .initializationTimeout(Duration.ofSeconds(30))
                .build();
        try {
            client.initialize();
            return action.apply(client);
        } catch (InvalidRequestException | AccessDeniedException | ExternalServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ExternalServiceException("GitHub MCP request failed", e);
        } finally {
            try {
                client.closeGracefully();
            } catch (Exception ignored) {
                // Request result is authoritative; close failures are non-fatal.
            }
        }
    }

    private JsonNode parseToolText(String text) {
        String candidate = text.trim();
        if (candidate.startsWith("```")) {
            candidate = candidate.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        try {
            return objectMapper.readTree(candidate);
        } catch (Exception e) {
            throw new ExternalServiceException("GitHub MCP returned a non-JSON tool result", e);
        }
    }

    private RepositoryCoordinates requireAllowed(RepositoryCoordinates coordinates) {
        RepositoryCoordinates safe = coordinatesPolicy.validate(coordinates);
        requireConfigured();
        String fullName = (safe.owner() + "/" + safe.name()).toLowerCase();
        if (!allowedRepositories.contains(fullName)) {
            throw new AccessDeniedException("Repository is outside the configured MCP scope");
        }
        return safe;
    }

    private void requireConfigured() {
        if (token.isBlank() || allowedRepositories.isEmpty()) {
            throw new InvalidRequestException(
                    "GitHub MCP access requires GITHUB_MCP_TOKEN and GITHUB_MCP_ALLOWED_REPOSITORIES");
        }
    }

    private Set<String> parseAllowlist(String raw) {
        Set<String> parsed = new HashSet<>();
        for (String entry : raw.split(",")) {
            String normalized = entry.trim().toLowerCase();
            if (normalized.matches("[a-z0-9_.-]+/[a-z0-9_.-]+")) {
                parsed.add(normalized);
            }
        }
        return Set.copyOf(parsed);
    }

    private JsonNode findRepository(JsonNode node, RepositoryCoordinates coordinates) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            String name = firstText(node, "name");
            String owner = node.path("owner").isObject()
                    ? firstText(node.path("owner"), "login", "name")
                    : firstText(node, "owner_login", "owner");
            if (coordinates.name().equalsIgnoreCase(name) && coordinates.owner().equalsIgnoreCase(owner)) {
                return node;
            }
        }
        for (JsonNode child : node) {
            JsonNode found = findRepository(child, coordinates);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private void collectTreeEntries(JsonNode node, List<RepositoryTreeEntry> entries) {
        if (node == null) {
            return;
        }
        if (node.isObject() && node.hasNonNull("path") && node.hasNonNull("type")) {
            entries.add(new RepositoryTreeEntry(
                    node.path("path").asText(),
                    firstText(node, "sha", "objectSha"),
                    node.path("size").asLong(0),
                    node.path("type").asText()));
        }
        for (JsonNode child : node) {
            collectTreeEntries(child, entries);
        }
    }

    private JsonNode findObjectWithField(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        if (node.isObject() && node.hasNonNull(field)) {
            return node;
        }
        for (JsonNode child : node) {
            JsonNode found = findObjectWithField(child, field);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private String findFirstField(JsonNode node, String field) {
        JsonNode found = findObjectWithField(node, field);
        return found == null ? "" : found.path(field).asText();
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            if (node.hasNonNull(field)) {
                return node.path(field).asText("");
            }
        }
        return "";
    }
}

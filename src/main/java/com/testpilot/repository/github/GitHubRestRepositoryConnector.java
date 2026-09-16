package com.testpilot.repository.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.common.exception.ExternalServiceException;
import com.testpilot.common.exception.InvalidRequestException;
import com.testpilot.repository.connector.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

@Component
public class GitHubRestRepositoryConnector implements RepositoryConnector {

    private static final int MAX_DISCOVERED_REPOSITORIES = 1_000;
    private static final int MAX_FILE_BYTES = 512 * 1024;

    private final ObjectMapper objectMapper;
    private final GitHubAppTokenProvider tokenProvider;
    private final GitHubCoordinatesPolicy coordinatesPolicy;
    private final HttpClient httpClient;
    private final String apiBase;

    public GitHubRestRepositoryConnector(
            ObjectMapper objectMapper,
            GitHubAppTokenProvider tokenProvider,
            GitHubCoordinatesPolicy coordinatesPolicy,
            @Value("${testpilot.github.api-base:https://api.github.com}") String apiBase) {
        this.objectMapper = objectMapper;
        this.tokenProvider = tokenProvider;
        this.coordinatesPolicy = coordinatesPolicy;
        this.apiBase = apiBase.replaceAll("/$", "");
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public RepositoryTransport transport() {
        return RepositoryTransport.GITHUB_APP_REST;
    }

    @Override
    public List<RemoteRepositoryMetadata> discoverRepositories(RepositoryAccessContext accessContext) {
        long installationId = requireInstallationId(accessContext);
        String token = tokenProvider.getInstallationToken(installationId, Optional.empty());
        List<RemoteRepositoryMetadata> repositories = new ArrayList<>();

        for (int page = 1; repositories.size() < MAX_DISCOVERED_REPOSITORIES; page++) {
            JsonNode response = getJson(
                    "/installation/repositories?per_page=100&page=" + page,
                    token);
            JsonNode items = response.path("repositories");
            if (!items.isArray()) {
                throw new ExternalServiceException("GitHub returned an invalid repository discovery response");
            }
            for (JsonNode item : items) {
                repositories.add(toMetadata(item));
            }
            if (items.size() < 100) {
                break;
            }
        }
        return List.copyOf(repositories);
    }

    @Override
    public RemoteRepositoryMetadata getRepository(
            RepositoryCoordinates coordinates,
            RepositoryAccessContext accessContext) {
        RepositoryCoordinates safe = coordinatesPolicy.validate(coordinates);
        String token = scopedToken(accessContext, safe.name());
        return toMetadata(getJson(repositoryPath(safe), token));
    }

    @Override
    public String resolveRevision(
            RepositoryCoordinates coordinates,
            String revision,
            RepositoryAccessContext accessContext) {
        RepositoryCoordinates safe = coordinatesPolicy.validate(coordinates);
        if (revision == null || revision.isBlank() || revision.length() > 255) {
            throw new InvalidRequestException("A branch, tag, or commit revision is required");
        }
        String token = scopedToken(accessContext, safe.name());
        JsonNode commit = getJson(
                repositoryPath(safe) + "/commits/" + queryEncode(revision),
                token);
        return coordinatesPolicy.requireCommitSha(commit.path("sha").asText());
    }

    @Override
    public List<RepositoryTreeEntry> listTree(
            RepositoryCoordinates coordinates,
            String commitSha,
            RepositoryAccessContext accessContext) {
        RepositoryCoordinates safe = coordinatesPolicy.validate(coordinates);
        String safeSha = coordinatesPolicy.requireCommitSha(commitSha);
        String token = scopedToken(accessContext, safe.name());
        JsonNode response = getJson(repositoryPath(safe) + "/git/trees/" + safeSha + "?recursive=1", token);
        if (response.path("truncated").asBoolean(false)) {
            throw new InvalidRequestException("Repository tree is too large for safe recursive ingestion");
        }

        List<RepositoryTreeEntry> entries = new ArrayList<>();
        for (JsonNode entry : response.path("tree")) {
            entries.add(new RepositoryTreeEntry(
                    entry.path("path").asText(),
                    entry.path("sha").asText(),
                    entry.path("size").asLong(0),
                    "120000".equals(entry.path("mode").asText()) ? "symlink" : entry.path("type").asText()));
        }
        return List.copyOf(entries);
    }

    @Override
    public RepositoryFileContent readFile(
            RepositoryCoordinates coordinates,
            String commitSha,
            RepositoryTreeEntry entry,
            RepositoryAccessContext accessContext) {
        RepositoryCoordinates safe = coordinatesPolicy.validate(coordinates);
        String safeSha = coordinatesPolicy.requireCommitSha(commitSha);
        if (entry.size() > MAX_FILE_BYTES) {
            throw new InvalidRequestException("Repository file exceeds the 512 KiB ingestion limit: " + entry.path());
        }
        String token = scopedToken(accessContext, safe.name());
        JsonNode response = getJson(
                repositoryPath(safe) + "/contents/" + encodePath(entry.path()) + "?ref=" + safeSha,
                token);
        if (!"base64".equals(response.path("encoding").asText())) {
            throw new InvalidRequestException("GitHub file content is not base64 encoded: " + entry.path());
        }
        byte[] content;
        try {
            content = Base64.getMimeDecoder().decode(response.path("content").asText());
        } catch (IllegalArgumentException e) {
            throw new ExternalServiceException("GitHub returned invalid file content", e);
        }
        if (content.length > MAX_FILE_BYTES) {
            throw new InvalidRequestException("Repository file exceeds the 512 KiB ingestion limit: " + entry.path());
        }
        return new RepositoryFileContent(entry.path(), entry.objectSha(), content);
    }

    private String scopedToken(RepositoryAccessContext context, String repositoryName) {
        return tokenProvider.getInstallationToken(requireInstallationId(context), Optional.of(repositoryName));
    }

    private long requireInstallationId(RepositoryAccessContext context) {
        if (context == null || context.installationId() == null || context.installationId() <= 0) {
            throw new InvalidRequestException("A valid GitHub App installation ID is required");
        }
        return context.installationId();
    }

    private JsonNode getJson(String path, String token) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/vnd.github+json")
                    .header("Authorization", "Bearer " + token)
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403 || response.statusCode() == 404) {
                throw new AccessDeniedException("Repository is outside the authorized GitHub installation scope");
            }
            if (response.statusCode() / 100 != 2) {
                throw new ExternalServiceException("GitHub repository request failed");
            }
            return objectMapper.readTree(response.body());
        } catch (AccessDeniedException | ExternalServiceException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExternalServiceException("GitHub repository request was interrupted", e);
        } catch (Exception e) {
            throw new ExternalServiceException("GitHub repository request failed", e);
        }
    }

    private RemoteRepositoryMetadata toMetadata(JsonNode repository) {
        JsonNode ownerNode = repository.path("owner");
        return new RemoteRepositoryMetadata(
                ownerNode.path("login").asText(),
                repository.path("name").asText(),
                repository.path("default_branch").asText(),
                repository.path("visibility").asText("private"));
    }

    private String repositoryPath(RepositoryCoordinates coordinates) {
        return "/repos/" + coordinates.owner() + "/" + coordinates.name();
    }

    private String encodePath(String path) {
        String[] segments = path.split("/");
        List<String> encoded = new ArrayList<>(segments.length);
        for (String segment : segments) {
            encoded.add(queryEncode(segment));
        }
        return String.join("/", encoded);
    }

    private String queryEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}

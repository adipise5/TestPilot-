package com.testpilot.delivery.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.common.exception.ExternalServiceException;
import com.testpilot.common.exception.InvalidRequestException;
import com.testpilot.common.validation.RepositoryPathPolicy;
import com.testpilot.delivery.service.GitBranchPolicy;
import com.testpilot.repository.connector.RepositoryCoordinates;
import com.testpilot.repository.github.GitHubAppTokenProvider;
import com.testpilot.repository.github.GitHubCoordinatesPolicy;
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
import java.util.List;
import java.util.Map;

@Component
public class GitHubPullRequestGateway implements GitHubDeliveryGateway {

    private static final int MAX_FILES = 100;
    private static final int MAX_TOTAL_CONTENT_CHARS = 2_000_000;

    private final ObjectMapper objectMapper;
    private final GitHubAppTokenProvider tokens;
    private final GitHubCoordinatesPolicy coordinatesPolicy;
    private final RepositoryPathPolicy pathPolicy;
    private final GitBranchPolicy branchPolicy;
    private final HttpClient httpClient;
    private final String apiBase;

    public GitHubPullRequestGateway(
            ObjectMapper objectMapper,
            GitHubAppTokenProvider tokens,
            GitHubCoordinatesPolicy coordinatesPolicy,
            RepositoryPathPolicy pathPolicy,
            GitBranchPolicy branchPolicy,
            @Value("${testpilot.github.api-base:https://api.github.com}") String apiBase) {
        this.objectMapper = objectMapper;
        this.tokens = tokens;
        this.coordinatesPolicy = coordinatesPolicy;
        this.pathPolicy = pathPolicy;
        this.branchPolicy = branchPolicy;
        this.apiBase = apiBase.replaceAll("/$", "");
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public GitHubDeliveryResult deliver(GitHubDeliveryRequest request) {
        RepositoryCoordinates coordinates = coordinatesPolicy.validate(
                new RepositoryCoordinates(request.owner(), request.repository()));
        String baseSha = coordinatesPolicy.requireCommitSha(request.baseCommitSha());
        String baseBranch = branchPolicy.requireSafeBaseBranch(request.baseBranch());
        String deliveryBranch = branchPolicy.requireDeliveryBranch(request.deliveryBranch(), baseBranch);
        validatePayload(request);

        String token = tokens.getDeliveryToken(request.installationId(), coordinates.name());
        String repositoryPath = "/repos/" + coordinates.owner() + "/" + coordinates.name();
        JsonNode baseCommit = request("GET", repositoryPath + "/git/commits/" + baseSha, token, null);
        String baseTreeSha = coordinatesPolicy.requireCommitSha(baseCommit.path("tree").path("sha").asText());

        List<Map<String, Object>> entries = request.changes().stream()
                .map(change -> Map.<String, Object>of(
                        "path", pathPolicy.validateRepositoryPath(change.path()),
                        "mode", "100644",
                        "type", "blob",
                        "content", change.content()))
                .toList();
        JsonNode tree = request("POST", repositoryPath + "/git/trees", token, Map.of(
                "base_tree", baseTreeSha,
                "tree", entries));
        String treeSha = coordinatesPolicy.requireCommitSha(tree.path("sha").asText());

        JsonNode commit = request("POST", repositoryPath + "/git/commits", token, Map.of(
                "message", request.commitMessage(),
                "tree", treeSha,
                "parents", List.of(baseSha)));
        String headSha = coordinatesPolicy.requireCommitSha(commit.path("sha").asText());

        boolean branchCreated = false;
        try {
            request("POST", repositoryPath + "/git/refs", token, Map.of(
                    "ref", "refs/heads/" + deliveryBranch,
                    "sha", headSha));
            branchCreated = true;
            JsonNode pullRequest = request("POST", repositoryPath + "/pulls", token, Map.of(
                    "title", request.pullRequestTitle(),
                    "head", deliveryBranch,
                    "base", baseBranch,
                    "body", request.pullRequestBody()));
            long number = pullRequest.path("number").asLong(0);
            String url = requireSafePullRequestUrl(pullRequest.path("html_url").asText());
            if (number <= 0) {
                throw new ExternalServiceException("GitHub returned an invalid pull request response");
            }
            return new GitHubDeliveryResult(number, url, headSha);
        } catch (RuntimeException error) {
            if (branchCreated) {
                deleteBranchBestEffort(repositoryPath, deliveryBranch, token);
            }
            throw error;
        }
    }

    private void validatePayload(GitHubDeliveryRequest request) {
        if (request.installationId() <= 0) {
            throw new InvalidRequestException("A valid GitHub App installation ID is required");
        }
        if (request.changes() == null || request.changes().isEmpty() || request.changes().size() > MAX_FILES) {
            throw new InvalidRequestException("GitHub delivery must contain between 1 and 100 files");
        }
        long totalChars = 0;
        for (DeliveryChange change : request.changes()) {
            if (change == null || change.content() == null || change.content().isBlank()) {
                throw new InvalidRequestException("GitHub delivery contains an empty file change");
            }
            pathPolicy.validateRepositoryPath(change.path());
            totalChars += change.content().length();
        }
        if (totalChars <= 0 || totalChars > MAX_TOTAL_CONTENT_CHARS) {
            throw new InvalidRequestException("GitHub delivery content exceeds the 2,000,000 character limit");
        }
        if (request.pullRequestTitle() == null || request.pullRequestTitle().isBlank()
                || request.pullRequestTitle().length() > 256
                || request.pullRequestBody() == null || request.pullRequestBody().isBlank()
                || request.pullRequestBody().length() > 60_000
                || request.commitMessage() == null || request.commitMessage().isBlank()
                || request.commitMessage().length() > 256) {
            throw new InvalidRequestException("GitHub delivery metadata is invalid or oversized");
        }
    }

    private JsonNode request(String method, String path, String token, Object body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/vnd.github+json")
                    .header("Authorization", "Bearer " + token)
                    .header("X-GitHub-Api-Version", "2022-11-28");
            if (body == null) {
                builder.GET();
            } else {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403 || response.statusCode() == 404) {
                throw new AccessDeniedException("GitHub App cannot access the requested repository delivery scope");
            }
            if (response.statusCode() == 409 || response.statusCode() == 422) {
                throw new InvalidRequestException(
                        "GitHub rejected the delivery; the base revision or delivery branch may have changed");
            }
            if (response.statusCode() / 100 != 2) {
                throw new ExternalServiceException("GitHub delivery request failed");
            }
            return response.body() == null || response.body().isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(response.body());
        } catch (AccessDeniedException | InvalidRequestException | ExternalServiceException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExternalServiceException("GitHub delivery request was interrupted", e);
        } catch (Exception e) {
            throw new ExternalServiceException("GitHub delivery request failed", e);
        }
    }

    private void deleteBranchBestEffort(String repositoryPath, String branch, String token) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + repositoryPath + "/git/refs/heads/" + encodePath(branch)))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/vnd.github+json")
                    .header("Authorization", "Bearer " + token)
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .DELETE()
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // The persisted rollback path tells a reviewer how to remove any surviving branch.
        }
    }

    private String requireSafePullRequestUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (value.length() > 1000
                    || !"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null) {
                throw new ExternalServiceException("GitHub returned an invalid pull request response");
            }
            return value;
        } catch (IllegalArgumentException error) {
            throw new ExternalServiceException("GitHub returned an invalid pull request response", error);
        }
    }

    private String encodePath(String value) {
        return java.util.Arrays.stream(value.split("/"))
                .map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(java.util.stream.Collectors.joining("/"));
    }
}

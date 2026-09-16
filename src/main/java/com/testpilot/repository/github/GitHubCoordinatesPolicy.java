package com.testpilot.repository.github;

import com.testpilot.common.exception.InvalidRequestException;
import com.testpilot.repository.connector.RepositoryCoordinates;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class GitHubCoordinatesPolicy {

    public RepositoryCoordinates fromUrl(String input) {
        try {
            var uri = java.net.URI.create(input.strip());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getQuery() != null
                    || uri.getFragment() != null || uri.getRawPath().contains("%")) {
                throw new IllegalArgumentException();
            }
            String path = uri.getPath().replaceFirst("/$", "");
            String[] parts = path.split("/", -1);
            if (parts.length != 3 || !parts[0].isEmpty()) throw new IllegalArgumentException();
            return validate(new RepositoryCoordinates(parts[1], parts[2].replaceFirst("\\.git$", "")));
        } catch (IllegalArgumentException ex) {
            throw new InvalidRequestException("Use a repository URL such as https://github.com/owner/repository (not a file or branch URL)");
        }
    }

    private static final Pattern SEGMENT = Pattern.compile("^[A-Za-z0-9_.-]{1,100}$");
    private static final Pattern COMMIT_SHA = Pattern.compile("^[a-fA-F0-9]{40}$");

    public RepositoryCoordinates validate(RepositoryCoordinates coordinates) {
        if (coordinates == null
                || coordinates.owner() == null
                || coordinates.name() == null
                || !SEGMENT.matcher(coordinates.owner()).matches()
                || !SEGMENT.matcher(coordinates.name()).matches()
                || coordinates.owner().startsWith(".")
                || coordinates.name().startsWith(".")) {
            throw new InvalidRequestException("GitHub repository owner and name are invalid");
        }
        return coordinates;
    }

    public String requireCommitSha(String commitSha) {
        if (commitSha == null || !COMMIT_SHA.matcher(commitSha).matches()) {
            throw new InvalidRequestException("GitHub revision did not resolve to a full commit SHA");
        }
        return commitSha.toLowerCase();
    }
}

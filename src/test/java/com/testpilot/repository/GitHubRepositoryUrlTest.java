package com.testpilot.repository;

import com.testpilot.common.exception.InvalidRequestException;
import com.testpilot.repository.github.GitHubCoordinatesPolicy;
import com.testpilot.repository.dto.ConnectRepositoryRequest;
import com.testpilot.repository.connector.RepositoryTransport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GitHubRepositoryUrlTest {
    private final GitHubCoordinatesPolicy policy = new GitHubCoordinatesPolicy();

    @Test void acceptsRepositoryUrlsAndOptionalGitSuffix() {
        for (String suffix : new String[]{"", "/", ".git", ".git/"}) {
            var coordinates = policy.fromUrl(" https://github.com/adipise5/TestPilot-" + suffix + " ");
            assertEquals("adipise5", coordinates.owner());
            assertEquals("TestPilot-", coordinates.name());
        }
    }

    @Test void rejectsUnsafeAndNonRepositoryUrls() {
        for (String url : new String[]{"http://github.com/a/b", "https://evil.com/a/b",
                "https://github.com.evil.com/a/b", "https://user@github.com/a/b", "https://github.com:443/a/b",
                "https://github.com/a/b/tree/main", "https://github.com/a/b?token=secret",
                "https://github.com/a/b#readme", "https://github.com/a/%2e%2e", "https://github.com/a/..",
                "git@github.com:a/b.git", "https://github.com/a"}) {
            assertThrows(InvalidRequestException.class, () -> policy.fromUrl(url), url);
        }
    }

    @Test void rejectsConflictingCoordinates() {
        assertThrows(InvalidRequestException.class, () -> new ConnectRepositoryRequest(
                RepositoryTransport.GITHUB_MCP, "different", "sample", null, null,
                "https://github.com/octocat/sample").normalized());
    }
}

package com.testpilot.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {
        "GITHUB_MCP_TOKEN=test-token",
        "GITHUB_MCP_ALLOWED_REPOSITORIES=example/project",
        "AI_PROVIDER=mock"
})
@ActiveProfiles("h2")
class SharedConfigurationTest {
    @Autowired Environment environment;

    @Test void h2ProfileReceivesSharedGitHubAndAiConfiguration() {
        assertEquals("test-token", environment.getProperty("testpilot.github.mcp.token"));
        assertEquals("example/project", environment.getProperty("testpilot.github.mcp.allowed-repositories"));
        assertEquals("mock", environment.getProperty("ai.provider"));
    }
}

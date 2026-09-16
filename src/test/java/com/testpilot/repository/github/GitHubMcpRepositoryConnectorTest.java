package com.testpilot.repository.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.common.validation.RepositoryPathPolicy;
import com.testpilot.repository.connector.RepositoryCoordinates;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GitHubMcpRepositoryConnectorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GitHubMcpRepositoryConnector connector = new GitHubMcpRepositoryConnector(
            objectMapper,
            new GitHubCoordinatesPolicy(),
            new RepositoryPathPolicy(),
            "https://api.githubcopilot.com/mcp/",
            "test-token",
            "adipise5/TestPilot-");

    @Test
    void recognizesMinimalSearchResultByFullName() throws Exception {
        JsonNode result = objectMapper.readTree("""
                {
                  "total_count": 1,
                  "items": [{
                    "name": "TestPilot-",
                    "full_name": "adipise5/TestPilot-",
                    "html_url": "https://github.com/adipise5/TestPilot-"
                  }]
                }
                """);

        JsonNode repository = connector.findRepository(
                result, new RepositoryCoordinates("adipise5", "TestPilot-"));

        assertNotNull(repository);
        assertEquals("adipise5/TestPilot-", repository.path("full_name").asText());
    }

    @Test
    void extractsTextFromEmbeddedMcpResource() {
        McpSchema.TextResourceContents resource = McpSchema.TextResourceContents
                .builder("repo://adipise5/TestPilot-/contents/example.java", "class Example {}")
                .mimeType("text/plain")
                .build();
        McpSchema.CallToolResult result = McpSchema.CallToolResult.builder()
                .addTextContent("successfully downloaded text file")
                .addContent(McpSchema.EmbeddedResource.builder(resource).build())
                .build();

        byte[] content = connector.extractFileContent(result, "example.java");

        assertArrayEquals("class Example {}".getBytes(StandardCharsets.UTF_8), content);
    }

    @Test
    void extractsBinaryFromEmbeddedMcpResource() {
        byte[] expected = new byte[]{0, 1, 2, 3};
        McpSchema.BlobResourceContents resource = McpSchema.BlobResourceContents
                .builder("repo://adipise5/TestPilot-/contents/example.bin", Base64.getEncoder().encodeToString(expected))
                .mimeType("application/octet-stream")
                .build();
        McpSchema.CallToolResult result = McpSchema.CallToolResult.builder()
                .addContent(McpSchema.EmbeddedResource.builder(resource).build())
                .build();

        byte[] content = connector.extractFileContent(result, "example.bin");

        assertArrayEquals(expected, content);
    }
}

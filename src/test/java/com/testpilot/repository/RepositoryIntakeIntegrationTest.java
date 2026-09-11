package com.testpilot.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.auth.dto.AuthResponse;
import com.testpilot.auth.dto.RegisterRequest;
import com.testpilot.auth.service.AuthService;
import com.testpilot.project.dto.CreateProjectRequest;
import com.testpilot.repository.connector.*;
import com.testpilot.repository.repository.RepositoryAuditEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@Transactional
class RepositoryIntakeIntegrationTest {

    private static final String COMMIT_SHA = "0123456789abcdef0123456789abcdef01234567";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AuthService authService;
    @Autowired RepositoryAuditEventRepository auditEventRepository;

    @MockBean RepositoryConnectorRegistry connectorRegistry;

    private RepositoryConnector connector;
    private String ownerToken;
    private String otherToken;
    private Long projectId;

    @BeforeEach
    void setUp() throws Exception {
        connector = mock(RepositoryConnector.class);
        when(connectorRegistry.require(RepositoryTransport.GITHUB_MCP)).thenReturn(connector);
        when(connector.getRepository(any(), any())).thenReturn(
                new RemoteRepositoryMetadata("octocat", "sample", "main", "private"));
        when(connector.resolveRevision(any(), anyString(), any())).thenReturn(COMMIT_SHA);
        when(connector.listTree(any(), eq(COMMIT_SHA), any())).thenReturn(List.of(
                new RepositoryTreeEntry("pom.xml", "pom-sha", 80, "blob"),
                new RepositoryTreeEntry("src/main/java/com/example/App.java", "source-sha", 80, "blob"),
                new RepositoryTreeEntry("src/test/java/com/example/AppTest.java", "test-sha", 80, "blob"),
                new RepositoryTreeEntry(".env", "secret-sha", 30, "blob"),
                new RepositoryTreeEntry("target/classes/App.class", "binary-sha", 100, "blob")));
        when(connector.readFile(any(), eq(COMMIT_SHA), any(), any())).thenAnswer(invocation -> {
            RepositoryTreeEntry entry = invocation.getArgument(2);
            String content = switch (entry.path()) {
                case "pom.xml" -> "<project><modelVersion>4.0.0</modelVersion></project>";
                case "src/main/java/com/example/App.java" ->
                        "package com.example; public class App { public int value() { return 1; } }";
                case "src/test/java/com/example/AppTest.java" ->
                        "package com.example; class AppTest {}";
                default -> throw new IllegalStateException("Excluded paths must not be fetched");
            };
            return new RepositoryFileContent(
                    entry.path(), entry.objectSha(), content.getBytes(StandardCharsets.UTF_8));
        });

        AuthResponse owner = authService.register(new RegisterRequest(
                "Repository Owner", "repo-owner@testpilot.com", "password"));
        AuthResponse other = authService.register(new RegisterRequest(
                "Other Developer", "repo-other@testpilot.com", "password"));
        ownerToken = "Bearer " + owner.token();
        otherToken = "Bearer " + other.token();
        projectId = createProject("Repository project", ownerToken);
    }

    @Test
    void shouldIngestExactCommitWithAllowlistAndRemainIdempotent() throws Exception {
        String request = """
                {"transport":"GITHUB_MCP","owner":"octocat","name":"sample","revision":"main"}
                """;
        String connectJson = mockMvc.perform(post("/api/projects/" + projectId + "/repository")
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.repository.selectedCommitSha").value(COMMIT_SHA))
                .andExpect(jsonPath("$.repository.installationScope")
                        .value("mcp-allowlist:repository:octocat/sample"))
                .andExpect(jsonPath("$.ingestion.status").value("COMPLETED"))
                .andExpect(jsonPath("$.ingestion.buildSystem").value("MAVEN"))
                .andExpect(jsonPath("$.ingestion.fileCount").value(3))
                .andReturn().getResponse().getContentAsString();

        long repositoryId = objectMapper.readTree(connectJson).path("repository").path("id").asLong();
        long ingestionId = objectMapper.readTree(connectJson).path("ingestion").path("id").asLong();

        mockMvc.perform(get("/api/repositories/" + repositoryId + "/catalog")
                        .header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].path").value("pom.xml"))
                .andExpect(jsonPath("$[1].path").value("src/main/java/com/example/App.java"))
                .andExpect(jsonPath("$[2].path").value("src/test/java/com/example/AppTest.java"));

        mockMvc.perform(get("/api/projects/" + projectId + "/files")
                        .header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].filePath")
                        .value("src/main/java/com/example/App.java"));

        mockMvc.perform(post("/api/repositories/" + repositoryId + "/ingestions")
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":\"main\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ingestionId))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        verify(connector, times(1)).listTree(any(), eq(COMMIT_SHA), any());
        verify(connector, times(3)).readFile(any(), eq(COMMIT_SHA), any(), any());

        mockMvc.perform(get("/api/repositories/" + repositoryId + "/catalog")
                        .header("Authorization", otherToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    @Test
    void shouldAuditRepositoryRejectedOutsideConnectorScope() throws Exception {
        when(connector.getRepository(any(), any()))
                .thenThrow(new AccessDeniedException("outside configured scope"));
        long auditCountBefore = auditEventRepository.count();

        mockMvc.perform(post("/api/projects/" + projectId + "/repository")
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transport":"GITHUB_MCP","owner":"forbidden","name":"private","revision":"main"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));

        org.junit.jupiter.api.Assertions.assertEquals(auditCountBefore + 1, auditEventRepository.count());
    }

    private Long createProject(String name, String token) throws Exception {
        String json = mockMvc.perform(post("/api/projects")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateProjectRequest(name, "fixture"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).path("id").asLong();
    }
}

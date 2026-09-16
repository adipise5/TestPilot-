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
    @Autowired com.testpilot.repository.repository.RepositoryIngestionRepository ingestionRepository;

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
                {"transport":"GITHUB_MCP","repositoryUrl":"https://github.com/octocat/sample.git"}
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

        verify(connector).getRepository(eq(new RepositoryCoordinates("octocat", "sample")), any());
        verify(connector).resolveRevision(any(), eq("main"), any());
        mockMvc.perform(get("/api/repositories/" + repositoryId + "/selection")
                        .header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commitSha").value(COMMIT_SHA))
                .andExpect(jsonPath("$.files.length()").value(5))
                .andExpect(jsonPath("$.files[0].path").value(".env"))
                .andExpect(jsonPath("$.files[0].disposition").value("EXCLUDED"))
                .andExpect(jsonPath("$.files[0].content").doesNotExist());
        mockMvc.perform(get("/api/repositories/" + repositoryId + "/selection")
                        .header("Authorization", otherToken)).andExpect(status().isForbidden());

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

    @Test
    void shouldUpgradeLegacyCatalogOnRefresh() throws Exception {
        String response = mockMvc.perform(post("/api/projects/" + projectId + "/repository")
                        .header("Authorization", ownerToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transport\":\"GITHUB_MCP\",\"owner\":\"octocat\",\"name\":\"sample\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var json = objectMapper.readTree(response);
        long repositoryId = json.path("repository").path("id").asLong();
        var legacy = ingestionRepository.findById(json.path("ingestion").path("id").asLong()).orElseThrow();
        legacy.setSelectionReport(null);
        ingestionRepository.saveAndFlush(legacy);
        mockMvc.perform(post("/api/repositories/" + repositoryId + "/ingestions")
                        .header("Authorization", ownerToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":\"main\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fileCount").value(3));
        mockMvc.perform(get("/api/repositories/" + repositoryId + "/selection").header("Authorization", ownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.files.length()").value(5));
        verify(connector, times(2)).listTree(any(), any(), any());
    }

    @Test
    void shouldCatalogPythonWithoutSendingItToJavaRunner() throws Exception {
        when(connector.listTree(any(), any(), any())).thenReturn(List.of(
                new RepositoryTreeEntry("app.py", "py-sha", 20, "blob")));
        when(connector.readFile(any(), any(), any(), any())).thenReturn(
                new RepositoryFileContent("app.py", "py-sha", "def add(a, b): return a + b".getBytes(StandardCharsets.UTF_8)));
        String response = mockMvc.perform(post("/api/projects/" + projectId + "/repository")
                        .header("Authorization", ownerToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transport\":\"GITHUB_MCP\",\"repositoryUrl\":\"https://github.com/octocat/sample\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.ingestion.fileCount").value(1))
                .andReturn().getResponse().getContentAsString();
        long repositoryId = objectMapper.readTree(response).path("repository").path("id").asLong();
        mockMvc.perform(get("/api/repositories/" + repositoryId + "/selection").header("Authorization", ownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.files[0].language").value("Python"))
                .andExpect(jsonPath("$.files[0].disposition").value("SOURCE_CODE"));
        mockMvc.perform(get("/api/projects/" + projectId + "/files").header("Authorization", ownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
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

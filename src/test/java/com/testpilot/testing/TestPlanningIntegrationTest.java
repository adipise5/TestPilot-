package com.testpilot.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.auth.dto.RegisterRequest;
import com.testpilot.auth.service.AuthService;
import com.testpilot.project.dto.CreateProjectRequest;
import com.testpilot.repository.connector.RepositoryTransport;
import com.testpilot.repository.entity.*;
import com.testpilot.repository.repository.*;
import com.testpilot.testing.repository.GeneratedTestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("h2") @Transactional
class TestPlanningIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AuthService auth;
    @Autowired ConnectedRepositoryRepository repositories;
    @Autowired RepositoryIngestionRepository ingestions;
    @Autowired RepositoryArtifactRepository artifacts;
    @Autowired GeneratedTestRepository executableTests;
    private String token;
    private String other;
    private long project;
    private long ingestion;

    @BeforeEach void setup() throws Exception {
        token = "Bearer " + auth.register(new RegisterRequest("Planner", "planner@test.local", "password")).token();
        other = "Bearer " + auth.register(new RegisterRequest("Other", "other-planner@test.local", "password")).token();
        var created = mvc.perform(post("/api/projects").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CreateProjectRequest("Polyglot", "fixture"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        project = mapper.readTree(created).path("id").asLong();
        var repo = repositories.saveAndFlush(new ConnectedRepository(project, RepositoryTransport.GITHUB_MCP,
                "owner", "repo", "main", "a".repeat(40), null, "fixture"));
        var catalog = new RepositoryIngestion(repo.getId(), "a".repeat(40));
        catalog.complete(BuildSystem.MIXED, 7, "b".repeat(64));
        ingestion = ingestions.saveAndFlush(catalog).getId();
        add("src/main/java/example/App.java", RepositoryArtifactKind.JAVA_SOURCE, "package example; public class App { public int add(int a,int b) {return a+b;} }");
        add("app.py", RepositoryArtifactKind.SOURCE_CODE, "import sqlite3\ndef connect(): return sqlite3.connect(':memory:')");
        add("web/app.ts", RepositoryArtifactKind.SOURCE_CODE, "export const add = (a: number, b: number) => a + b;");
        add("web/helper.js", RepositoryArtifactKind.SOURCE_CODE, "export const double = x => x * 2;");
        add("main.go", RepositoryArtifactKind.SOURCE_CODE, "package main");
        add("web/package.json", RepositoryArtifactKind.BUILD_MANIFEST, "{\"devDependencies\":{\"vitest\":\"1\"}}");
        add("tests/test_existing.py", RepositoryArtifactKind.EXISTING_TEST, "def test_old(): assert 1 == 1");
    }

    private void add(String path, RepositoryArtifactKind kind, String content) throws Exception {
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        artifacts.saveAndFlush(new RepositoryArtifact(ingestion, path, "a".repeat(40), hash, kind, content.length(), content));
    }

    @Test void plansMultipleLanguagesPersistsDraftsAndNeverCreatesExecutableTests() throws Exception {
        var response = mvc.perform(get("/api/projects/" + project + "/test-plan").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(12))
                .andExpect(jsonPath("$.unsupported[0].path").value("main.go"))
                .andReturn().getResponse().getContentAsString();
        var plan = mapper.readTree(response);
        long before = executableTests.count();
        int generated = 0;
        for (var item : plan.path("items")) {
            if (!item.path("applicable").asBoolean()) continue;
            var body = mapper.createObjectNode().put("snapshotId", plan.path("snapshotId").asText()).put("planId", item.path("id").asText());
            mvc.perform(post("/api/projects/" + project + "/test-drafts").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.result.status").value("MOCK_SCAFFOLD"))
                    .andExpect(jsonPath("$.result.executionStatus").value("NOT_EXECUTED"));
            generated++;
        }
        assertTrue(generated >= 4);
        assertEquals(before, executableTests.count());
        mvc.perform(get("/api/projects/" + project + "/test-drafts").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(generated));
    }

    @Test void enforcesAuthorizationAndRejectsStaleOrInventedPlans() throws Exception {
        var response = mvc.perform(get("/api/projects/" + project + "/test-plan").header("Authorization", token))
                .andReturn().getResponse().getContentAsString();
        var plan = mapper.readTree(response);
        var body = mapper.createObjectNode().put("snapshotId", plan.path("snapshotId").asText()).put("planId", plan.path("items").get(0).path("id").asText());
        for (String endpoint : List.of("test-plan", "test-drafts")) {
            mvc.perform(get("/api/projects/" + project + "/" + endpoint).header("Authorization", other)).andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/projects/" + project + "/test-drafts").header("Authorization", other)
                .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isForbidden());
        var unknown = body.deepCopy().put("planId", "0".repeat(64));
        mvc.perform(post("/api/projects/" + project + "/test-drafts").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(unknown.toString())).andExpect(status().isBadRequest());
        add("new.py", RepositoryArtifactKind.SOURCE_CODE, "def new(): return 1");
        mvc.perform(post("/api/projects/" + project + "/test-drafts").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/projects/" + project + "/test-drafts").header("Authorization", token))
                .andExpect(jsonPath("$.length()").value(0));
    }
    @Test void rejectsCatalogWithInvalidContentHash() throws Exception {
        artifacts.saveAndFlush(new RepositoryArtifact(ingestion, "tampered.py", "a".repeat(40), "0".repeat(64),
                RepositoryArtifactKind.SOURCE_CODE, 9, "value = 1"));
        mvc.perform(get("/api/projects/" + project + "/test-plan").header("Authorization", token))
                .andExpect(status().isBadRequest());
    }

}

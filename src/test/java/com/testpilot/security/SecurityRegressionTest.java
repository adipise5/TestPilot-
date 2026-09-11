package com.testpilot.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.auth.dto.AuthResponse;
import com.testpilot.auth.dto.RegisterRequest;
import com.testpilot.auth.service.AuthService;
import com.testpilot.failure.entity.FailureAnalysis;
import com.testpilot.failure.entity.FixSuggestion;
import com.testpilot.failure.entity.Severity;
import com.testpilot.failure.repository.FailureAnalysisRepository;
import com.testpilot.failure.repository.FixSuggestionRepository;
import com.testpilot.project.dto.CreateCodeFileRequest;
import com.testpilot.project.dto.CreateProjectRequest;
import com.testpilot.testing.entity.*;
import com.testpilot.testing.repository.GeneratedTestRepository;
import com.testpilot.testing.repository.TestResultRepository;
import com.testpilot.testing.repository.TestRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@Transactional
class SecurityRegressionTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AuthService authService;
    @Autowired TestRunRepository testRunRepository;
    @Autowired GeneratedTestRepository generatedTestRepository;
    @Autowired TestResultRepository testResultRepository;
    @Autowired FailureAnalysisRepository failureAnalysisRepository;
    @Autowired FixSuggestionRepository fixSuggestionRepository;

    private String ownerToken;
    private String attackerToken;
    private Long projectId;
    private Long fileId;
    private Long testRunId;
    private Long testResultId;
    private Long failureAnalysisId;
    private Long fixSuggestionId;

    @BeforeEach
    void setUp() throws Exception {
        AuthResponse owner = authService.register(new RegisterRequest(
                "Owner", "security-owner@testpilot.com", "password"));
        AuthResponse attacker = authService.register(new RegisterRequest(
                "Attacker", "security-attacker@testpilot.com", "password"));
        ownerToken = "Bearer " + owner.token();
        attackerToken = "Bearer " + attacker.token();

        String projectJson = mockMvc.perform(post("/api/projects")
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateProjectRequest("Owned project", "Authorization fixture"))))
                .andReturn().getResponse().getContentAsString();
        projectId = objectMapper.readTree(projectJson).path("id").asLong();

        String fileJson = mockMvc.perform(post("/api/projects/" + projectId + "/files")
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCodeFileRequest(
                                "Owned.java",
                                "src/main/java/com/example/Owned.java",
                                "package com.example; public class Owned {}"))))
                .andReturn().getResponse().getContentAsString();
        fileId = objectMapper.readTree(fileJson).path("id").asLong();

        TestRun testRun = testRunRepository.save(new TestRun(projectId));
        testRunId = testRun.getId();
        generatedTestRepository.save(new GeneratedTest(
                testRunId, "Owned.java", "com.example.OwnedTest",
                "package com.example; class OwnedTest {}"));
        TestResult result = testResultRepository.save(new TestResult(
                testRunId, "OwnedTest.fails", TestResultStatus.FAILED, "failed", "trace", 0.1));
        testResultId = result.getId();
        FailureAnalysis analysis = failureAnalysisRepository.save(new FailureAnalysis(
                testResultId, "fixture", Severity.LOW, "fails", "fixture", 1.0));
        failureAnalysisId = analysis.getId();
        FixSuggestion fix = fixSuggestionRepository.save(new FixSuggestion(
                failureAnalysisId, "before", "after", "fixture"));
        fixSuggestionId = fix.getId();
    }

    @Test
    void shouldRejectCrossUserAccessAcrossEveryIdBasedProjectResource() throws Exception {
        String updateProject = "{\"name\":\"Stolen project\",\"description\":\"no\"}";
        String generatedTest = """
                {"sourceFile":"Owned.java","testClass":"com.example.OtherTest","testCode":"class OtherTest {}"}
                """;

        List<MockHttpServletRequestBuilder> forbiddenRequests = List.of(
                get("/api/projects/" + projectId),
                put("/api/projects/" + projectId).contentType(MediaType.APPLICATION_JSON).content(updateProject),
                delete("/api/projects/" + projectId),
                get("/api/projects/" + projectId + "/files"),
                get("/api/projects/" + projectId + "/files/" + fileId),
                post("/api/projects/" + projectId + "/analyze"),
                post("/api/projects/" + projectId + "/test-runs"),
                post("/api/projects/" + projectId + "/test-runs/auto"),
                get("/api/projects/" + projectId + "/test-runs"),
                get("/api/test-runs/" + testRunId),
                post("/api/test-runs/" + testRunId + "/execute"),
                post("/api/test-runs/" + testRunId + "/generate-tests"),
                post("/api/test-runs/" + testRunId + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(generatedTest),
                post("/api/failures/" + testResultId + "/analyze"),
                get("/api/failures/" + testResultId),
                get("/api/failures/analysis/" + failureAnalysisId + "/fix"),
                post("/api/fix-suggestions/" + fixSuggestionId + "/accept"),
                post("/api/fix-suggestions/" + fixSuggestionId + "/reject"),
                get("/api/projects/" + projectId + "/repository")
        );

        for (MockHttpServletRequestBuilder request : forbiddenRequests) {
            mockMvc.perform(request.header("Authorization", attackerToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
        }
    }

    @Test
    void shouldRejectTraversalAndFilenameMismatchBeforePersistence() throws Exception {
        CreateCodeFileRequest traversal = new CreateCodeFileRequest(
                "Escape.java", "src/main/java/../../../../tmp/Escape.java", "class Escape {}");
        mockMvc.perform(post("/api/projects/" + projectId + "/files")
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(traversal)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        CreateCodeFileRequest mismatch = new CreateCodeFileRequest(
                "Different.java", "src/main/java/com/example/Actual.java", "class Actual {}");
        mockMvc.perform(post("/api/projects/" + projectId + "/files")
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mismatch)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }
}

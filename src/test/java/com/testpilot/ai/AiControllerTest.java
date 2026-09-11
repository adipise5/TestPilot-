package com.testpilot.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.auth.dto.AuthResponse;
import com.testpilot.auth.dto.RegisterRequest;
import com.testpilot.auth.service.AuthService;
import com.testpilot.project.dto.CreateCodeFileRequest;
import com.testpilot.project.dto.CreateProjectRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@Transactional
class AiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    private String devToken;
    private Long projectId;

    @BeforeEach
    void setUp() throws Exception {
        AuthResponse dev = authService.register(new RegisterRequest("AI Dev", "aidev@testpilot.com", "password"));
        devToken = "Bearer " + dev.token();

        CreateProjectRequest projectReq = new CreateProjectRequest("AI Test Project", "Project for AI analysis");
        String projRes = mockMvc.perform(post("/api/projects")
                        .header("Authorization", devToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(projectReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        projectId = objectMapper.readTree(projRes).get("id").asLong();

        String javaCode = """
                package com.example;
                
                public class Calculator {
                    public int add(int a, int b) { return a + b; }
                    public int divide(int a, int b) { return a / b; }
                }
                """;

        CreateCodeFileRequest fileReq = new CreateCodeFileRequest("Calculator.java", "src/main/java/com/example/Calculator.java", javaCode);
        mockMvc.perform(post("/api/projects/" + projectId + "/files")
                        .header("Authorization", devToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fileReq)))
                .andExpect(status().isCreated());
    }

    @Test
    void shouldAnalyzeProjectCode() throws Exception {
        mockMvc.perform(post("/api/projects/" + projectId + "/analyze")
                        .header("Authorization", devToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classes[0]").value("Calculator"))
                .andExpect(jsonPath("$.summary").exists())
                .andExpect(jsonPath("$.edgeCases").isArray());
    }

    @Test
    void shouldGenerateTestsForTestRun() throws Exception {
        String trRes = mockMvc.perform(post("/api/projects/" + projectId + "/test-runs")
                        .header("Authorization", devToken))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Long testRunId = objectMapper.readTree(trRes).get("id").asLong();

        mockMvc.perform(post("/api/test-runs/" + testRunId + "/generate-tests")
                        .header("Authorization", devToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testClass").exists())
                .andExpect(jsonPath("$.fullTestCode").exists());
    }
}

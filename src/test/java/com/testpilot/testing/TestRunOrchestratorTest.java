package com.testpilot.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.auth.dto.AuthResponse;
import com.testpilot.auth.dto.RegisterRequest;
import com.testpilot.auth.service.AuthService;
import com.testpilot.project.dto.CreateCodeFileRequest;
import com.testpilot.project.dto.CreateProjectRequest;
import com.testpilot.testing.entity.TestRunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class TestRunOrchestratorTest {

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
        AuthResponse dev = authService.register(new RegisterRequest("Orchestration Dev", "orchestrator@testpilot.com", "password"));
        devToken = "Bearer " + dev.token();

        CreateProjectRequest projectReq = new CreateProjectRequest("Async Test Project", "Asynchronous test generation project");
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
    void shouldOrchestrateAutomatedTestRunAsynchronously() throws Exception {
        // 1. Trigger automated TestRun (Returns 202 Accepted immediately)
        String asyncRes = mockMvc.perform(post("/api/projects/" + projectId + "/test-runs/auto")
                        .header("Authorization", devToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();

        Long testRunId = objectMapper.readTree(asyncRes).get("id").asLong();

        // 2. Poll GET /api/test-runs/{id} until status reaches COMPLETED
        String finalStatus = "PENDING";
        int maxPolls = 15;
        while (!finalStatus.equals("COMPLETED") && !finalStatus.equals("FAILED") && maxPolls > 0) {
            Thread.sleep(500);
            String pollRes = mockMvc.perform(get("/api/test-runs/" + testRunId)
                            .header("Authorization", devToken))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            finalStatus = objectMapper.readTree(pollRes).get("status").asText();
            maxPolls--;
        }

        assertEquals("COMPLETED", finalStatus);
    }
}

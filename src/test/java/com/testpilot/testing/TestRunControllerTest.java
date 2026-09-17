package com.testpilot.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.auth.dto.AuthResponse;
import com.testpilot.auth.dto.RegisterRequest;
import com.testpilot.auth.service.AuthService;
import com.testpilot.project.dto.CreateCodeFileRequest;
import com.testpilot.project.dto.CreateProjectRequest;
import com.testpilot.testing.dto.SaveGeneratedTestRequest;
import com.testpilot.testing.entity.TestRunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@Transactional
class TestRunControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    // Controller/persistence integration only; real runtime behavior is exercised
    // separately by the Docker worker smoke suite, never by a host Maven fallback.
    @org.springframework.boot.test.mock.mockito.MockBean
    private com.testpilot.testing.execution.sandbox.ContainerProcess container;

    private String devToken;
    private Long projectId;

    @BeforeEach
    void setUp() throws Exception {
        AuthResponse dev = authService.register(new RegisterRequest("Tester Dev", "tester@testpilot.com", "password"));
        devToken = "Bearer " + dev.token();

        // Create Project
        CreateProjectRequest projectReq = new CreateProjectRequest("Math Engine", "Math calculation project");
        String projRes = mockMvc.perform(post("/api/projects")
                        .header("Authorization", devToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(projectReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        projectId = objectMapper.readTree(projRes).get("id").asLong();

        // Upload Source File
        String sourceCode = """
                package com.example;
                
                public class Calculator {
                    public int add(int a, int b) {
                        return a + b;
                    }
                }
                """;

        CreateCodeFileRequest fileReq = new CreateCodeFileRequest("Calculator.java", "src/main/java/com/example/Calculator.java", sourceCode);
        mockMvc.perform(post("/api/projects/" + projectId + "/files")
                        .header("Authorization", devToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fileReq)))
                .andExpect(status().isCreated());
    }

    @Test
    void shouldCreateSaveAndExecuteTestRun() throws Exception {
        // 1. Create Test Run
        String testRunRes = mockMvc.perform(post("/api/projects/" + projectId + "/test-runs")
                        .header("Authorization", devToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();

        Long testRunId = objectMapper.readTree(testRunRes).get("id").asLong();

        // 2. Register Generated Test Code
        String generatedTestCode = """
                package com.example;
                
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                
                public class CalculatorTest {
                    @Test
                    void shouldAddNumbers() {
                        Calculator calculator = new Calculator();
                        assertEquals(5, calculator.add(2, 3));
                    }
                }
                """;

        SaveGeneratedTestRequest genReq = new SaveGeneratedTestRequest("Calculator.java", "com.example.CalculatorTest", generatedTestCode);
        mockMvc.perform(post("/api/test-runs/" + testRunId + "/tests")
                        .header("Authorization", devToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(genReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.testClass").value("com.example.CalculatorTest"));

        org.mockito.Mockito.when(container.run(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(
                new com.testpilot.testing.execution.sandbox.ContainerProcess.Result(0, false, false,
                        "{\"outcome\":\"SUCCESS\",\"exitCode\":0,\"output\":\"fixture worker result\",\"tests\":[{\"name\":\"com.example.CalculatorTest.shouldAddNumbers\",\"status\":\"PASSED\",\"message\":\"\",\"seconds\":0.01}]}"));

        // 3. Execute Test Run
        mockMvc.perform(post("/api/test-runs/" + testRunId + "/execute")
                        .header("Authorization", devToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.executionOutcome").value("SUCCESS"))
                .andExpect(jsonPath("$.processExitCode").value(0))
                .andExpect(jsonPath("$.executionOutput").isNotEmpty())
                .andExpect(jsonPath("$.testResults[0].status").value("PASSED"))
                .andExpect(jsonPath("$.testResults[0].testName").value("com.example.CalculatorTest.shouldAddNumbers"));
    }
}

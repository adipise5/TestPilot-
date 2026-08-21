package com.testpilot.failure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.auth.dto.AuthResponse;
import com.testpilot.auth.dto.RegisterRequest;
import com.testpilot.auth.entity.Role;
import com.testpilot.auth.service.AuthService;
import com.testpilot.project.dto.CreateCodeFileRequest;
import com.testpilot.project.dto.CreateProjectRequest;
import com.testpilot.testing.entity.TestResult;
import com.testpilot.testing.entity.TestResultStatus;
import com.testpilot.testing.entity.TestRun;
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
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@Transactional
class FailureControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private TestRunRepository testRunRepository;

    @Autowired
    private TestResultRepository testResultRepository;

    private String devToken;
    private Long testResultId;

    @BeforeEach
    void setUp() throws Exception {
        AuthResponse dev = authService.register(new RegisterRequest("Debug Dev", "debug@testpilot.com", "password", Role.DEVELOPER));
        devToken = "Bearer " + dev.token();

        // Create project
        CreateProjectRequest projectReq = new CreateProjectRequest("Debug Project", "Project with failing tests");
        String projRes = mockMvc.perform(post("/api/projects")
                        .header("Authorization", devToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(projectReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Long projectId = objectMapper.readTree(projRes).get("id").asLong();

        // Upload source file
        CreateCodeFileRequest fileReq = new CreateCodeFileRequest("Calculator.java", "src/main/java/com/example/Calculator.java", "public class Calculator { public int divide(int a, int b) { return a / b; } }");
        mockMvc.perform(post("/api/projects/" + projectId + "/files")
                        .header("Authorization", devToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fileReq)))
                .andExpect(status().isCreated());

        // Create TestRun & TestResult
        TestRun testRun = testRunRepository.save(new TestRun(projectId));
        TestResult testResult = testResultRepository.save(new TestResult(
                testRun.getId(),
                "com.example.CalculatorTest.shouldDivideByZero",
                TestResultStatus.FAILED,
                "/ by zero",
                "java.lang.ArithmeticException: / by zero at com.example.Calculator.divide(Calculator.java:2)",
                0.015
        ));

        testResultId = testResult.getId();
    }

    @Test
    void shouldAnalyzeFailureAndSupportHumanReviewAcceptance() throws Exception {
        // 1. Run failure analysis
        String analysisRes = mockMvc.perform(post("/api/failures/" + testResultId + "/analyze")
                        .header("Authorization", devToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rootCause").exists())
                .andExpect(jsonPath("$.affectedMethod").exists())
                .andReturn().getResponse().getContentAsString();

        Long analysisId = objectMapper.readTree(analysisRes).get("id").asLong();

        // 2. Fetch fix suggestion
        String fixRes = mockMvc.perform(get("/api/failures/analysis/" + analysisId + "/fix")
                        .header("Authorization", devToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.suggestedCode").exists())
                .andReturn().getResponse().getContentAsString();

        Long fixId = objectMapper.readTree(fixRes).get("id").asLong();

        // 3. Accept fix suggestion
        mockMvc.perform(post("/api/fix-suggestions/" + fixId + "/accept")
                        .header("Authorization", devToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }
}

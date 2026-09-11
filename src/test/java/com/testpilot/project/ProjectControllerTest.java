package com.testpilot.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.auth.dto.AuthResponse;
import com.testpilot.auth.dto.CreateManagedUserRequest;
import com.testpilot.auth.dto.LoginRequest;
import com.testpilot.auth.dto.RegisterRequest;
import com.testpilot.auth.entity.Role;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@Transactional
class ProjectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    private String dev1Token;
    private String dev2Token;
    private String reviewerToken;

    @BeforeEach
    void setUp() {
        AuthResponse dev1 = authService.register(new RegisterRequest("Dev One", "dev1@testpilot.com", "password"));
        AuthResponse dev2 = authService.register(new RegisterRequest("Dev Two", "dev2@testpilot.com", "password"));
        authService.createManagedUser(new CreateManagedUserRequest(
                "Rev One", "rev1@testpilot.com", "reviewer-password", Role.REVIEWER));
        AuthResponse reviewer = authService.login(new LoginRequest("rev1@testpilot.com", "reviewer-password"));

        dev1Token = "Bearer " + dev1.token();
        dev2Token = "Bearer " + dev2.token();
        reviewerToken = "Bearer " + reviewer.token();
    }

    @Test
    void shouldCreateProjectSuccessfully() throws Exception {
        CreateProjectRequest request = new CreateProjectRequest("Banking System", "Core banking services");

        mockMvc.perform(post("/api/projects")
                        .header("Authorization", dev1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Banking System"))
                .andExpect(jsonPath("$.description").value("Core banking services"));
    }

    @Test
    void shouldPreventDev2FromAccessingDev1Project() throws Exception {
        // Dev 1 creates project
        CreateProjectRequest request = new CreateProjectRequest("Secret Engine", "Top secret AI algorithm");
        String responseContent = mockMvc.perform(post("/api/projects")
                        .header("Authorization", dev1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Long projectId = objectMapper.readTree(responseContent).get("id").asLong();

        // Dev 1 can access own project
        mockMvc.perform(get("/api/projects/" + projectId)
                        .header("Authorization", dev1Token))
                .andExpect(status().isOk());

        // Dev 2 trying to access Dev 1's project must be rejected with 403 Forbidden
        mockMvc.perform(get("/api/projects/" + projectId)
                        .header("Authorization", dev2Token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    @Test
    void shouldAllowReviewerToAccessDev1Project() throws Exception {
        CreateProjectRequest request = new CreateProjectRequest("Payment Service", "Payment gateway integration");
        String responseContent = mockMvc.perform(post("/api/projects")
                        .header("Authorization", dev1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Long projectId = objectMapper.readTree(responseContent).get("id").asLong();

        // Reviewer can access Dev 1's project
        mockMvc.perform(get("/api/projects/" + projectId)
                        .header("Authorization", reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Payment Service"));
    }

    @Test
    void shouldUploadAndRetrieveCodeFile() throws Exception {
        CreateProjectRequest projectReq = new CreateProjectRequest("Calculator App", "Simple math app");
        String projResponse = mockMvc.perform(post("/api/projects")
                        .header("Authorization", dev1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(projectReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Long projectId = objectMapper.readTree(projResponse).get("id").asLong();

        String javaSourceCode = """
                package com.example;
                
                public class Calculator {
                    public int add(int a, int b) {
                        return a + b;
                    }
                }
                """;

        CreateCodeFileRequest fileReq = new CreateCodeFileRequest("Calculator.java", "src/main/java/com/example/Calculator.java", javaSourceCode);

        // Upload code file
        mockMvc.perform(post("/api/projects/" + projectId + "/files")
                        .header("Authorization", dev1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fileReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileName").value("Calculator.java"))
                .andExpect(jsonPath("$.filePath").value("src/main/java/com/example/Calculator.java"));

        // Retrieve code files
        mockMvc.perform(get("/api/projects/" + projectId + "/files")
                        .header("Authorization", dev1Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fileName").value("Calculator.java"))
                .andExpect(jsonPath("$[0].content").value(javaSourceCode));
    }
}

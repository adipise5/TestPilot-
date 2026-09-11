package com.testpilot.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.auth.dto.LoginRequest;
import com.testpilot.auth.dto.RegisterRequest;
import com.testpilot.auth.dto.CreateManagedUserRequest;
import com.testpilot.auth.entity.Role;
import com.testpilot.auth.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@Transactional
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    @Test
    void shouldRegisterUserSuccessfully() throws Exception {
        RegisterRequest request = new RegisterRequest(
                "Jane Doe",
                "jane.doe@example.com",
                "password123"
        );

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.type").value("Bearer"))
                .andExpect(jsonPath("$.name").value("Jane Doe"))
                .andExpect(jsonPath("$.email").value("jane.doe@example.com"))
                .andExpect(jsonPath("$.role").value("DEVELOPER"));
    }

    @Test
    void shouldFailRegistrationOnDuplicateEmail() throws Exception {
        RegisterRequest request = new RegisterRequest(
                "Alice Smith",
                "alice@example.com",
                "password123"
        );

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        // Second registration with duplicate email
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("DUPLICATE_EMAIL"));
    }

    @Test
    void shouldIgnorePrivilegedRoleDuringPublicRegistrationAndLoginAsDeveloper() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Bob Johnson","email":"bob@example.com","password":"securePass123","role":"ADMIN"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("DEVELOPER"));

        LoginRequest loginRequest = new LoginRequest("bob@example.com", "securePass123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.email").value("bob@example.com"))
                .andExpect(jsonPath("$.role").value("DEVELOPER"));
    }

    @Test
    void shouldRejectLoginWithWrongPassword() throws Exception {
        RegisterRequest registerRequest = new RegisterRequest(
                "Charlie Brown",
                "charlie@example.com",
                "correctPassword"
        );

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        LoginRequest loginRequest = new LoginRequest("charlie@example.com", "wrongPassword");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"));
    }

    @Test
    void shouldRestrictManagedRoleAssignmentToAdministrators() throws Exception {
        authService.createManagedUser(new CreateManagedUserRequest(
                "System Admin", "managed-admin@example.com", "administrator-password", Role.ADMIN));
        String adminToken = "Bearer " + authService.login(
                new LoginRequest("managed-admin@example.com", "administrator-password")).token();
        String developerToken = "Bearer " + authService.register(
                new RegisterRequest("Developer", "managed-dev@example.com", "password")).token();
        String reviewerRequest = """
                {"name":"Reviewer","email":"reviewer@example.com","password":"reviewer-password","role":"REVIEWER"}
                """;

        mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewerRequest))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));

        mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewerRequest))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("REVIEWER"));
    }
}

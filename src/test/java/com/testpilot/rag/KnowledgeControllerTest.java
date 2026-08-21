package com.testpilot.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.auth.dto.AuthResponse;
import com.testpilot.auth.dto.RegisterRequest;
import com.testpilot.auth.entity.Role;
import com.testpilot.auth.service.AuthService;
import com.testpilot.rag.dto.CreateKnowledgeDocumentRequest;
import com.testpilot.rag.dto.RagQueryRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@Transactional
class KnowledgeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    private String adminToken;
    private String devToken;

    @BeforeEach
    void setUp() {
        AuthResponse admin = authService.register(new RegisterRequest("Sys Admin", "admin@testpilot.com", "password", Role.ADMIN));
        AuthResponse dev = authService.register(new RegisterRequest("Regular Dev", "dev@testpilot.com", "password", Role.DEVELOPER));

        adminToken = "Bearer " + admin.token();
        devToken = "Bearer " + dev.token();
    }

    @Test
    void shouldAllowAdminToIngestKnowledgeDocument() throws Exception {
        CreateKnowledgeDocumentRequest request = new CreateKnowledgeDocumentRequest(
                "JUnit 5 Exception Testing Guidelines",
                "JUnit 5 Official Docs",
                "Use assertThrows(ArithmeticException.class, () -> calculator.divide(1, 0)) to test exception handling in JUnit 5."
        );

        mockMvc.perform(post("/api/knowledge")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.title").value("JUnit 5 Exception Testing Guidelines"));
    }

    @Test
    void shouldRejectNonAdminFromIngestingKnowledgeDocument() throws Exception {
        CreateKnowledgeDocumentRequest request = new CreateKnowledgeDocumentRequest(
                "Secret Rule",
                "Internal",
                "Testing content"
        );

        mockMvc.perform(post("/api/knowledge")
                        .header("Authorization", devToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    @Test
    void shouldQueryKnowledgeBaseSuccessfully() throws Exception {
        // 1. Ingest doc as Admin
        CreateKnowledgeDocumentRequest ingestReq = new CreateKnowledgeDocumentRequest(
                "Mockito Integration Guide",
                "Mockito Docs",
                "Use @Mock and when(service.find()).thenReturn(data) for isolating external dependencies in Spring Boot unit tests."
        );

        mockMvc.perform(post("/api/knowledge")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ingestReq)))
                .andExpect(status().isCreated());

        // 2. Query knowledge base as Dev
        RagQueryRequest queryReq = new RagQueryRequest("How to mock dependencies in Spring Boot unit tests?", 3);

        mockMvc.perform(post("/api/knowledge/query")
                        .header("Authorization", devToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(queryReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").exists())
                .andExpect(jsonPath("$[0].similarityScore").exists());
    }
}

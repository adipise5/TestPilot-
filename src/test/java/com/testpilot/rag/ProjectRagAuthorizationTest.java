package com.testpilot.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.auth.dto.AuthResponse;
import com.testpilot.auth.dto.RegisterRequest;
import com.testpilot.auth.service.AuthService;
import com.testpilot.project.entity.Project;
import com.testpilot.project.repository.ProjectRepository;
import com.testpilot.rag.dto.ProjectRagQueryRequest;
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
class ProjectRagAuthorizationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AuthService auth;
    @Autowired private ProjectRepository projects;

    @Test
    void projectRagQueryRequiresProjectReadAccessBeforeRetrieval() throws Exception {
        AuthResponse owner = auth.register(new RegisterRequest("RAG Owner", "rag-owner@testpilot.com", "password"));
        AuthResponse intruder = auth.register(new RegisterRequest("RAG Intruder", "rag-intruder@testpilot.com", "password"));
        Project project = projects.save(new Project("Private RAG", "isolated", owner.userId()));
        var request = new ProjectRagQueryRequest(
                "private symbols", "cccccccccccccccccccccccccccccccccccccccc", 5, 1000);

        mockMvc.perform(post("/api/projects/" + project.getId() + "/rag/query")
                        .header("Authorization", "Bearer " + intruder.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));

        mockMvc.perform(post("/api/projects/" + project.getId() + "/rag/query")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queryHash").isNotEmpty())
                .andExpect(jsonPath("$.results").isArray());
    }
}

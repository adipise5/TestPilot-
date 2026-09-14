package com.testpilot.observability;

import com.testpilot.auth.dto.AuthResponse;
import com.testpilot.auth.dto.RegisterRequest;
import com.testpilot.auth.service.AuthService;
import com.testpilot.project.entity.Project;
import com.testpilot.project.repository.ProjectRepository;
import com.testpilot.testing.entity.TestRun;
import com.testpilot.testing.repository.TestRunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@Transactional
class ObservabilityAuthorizationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AuthService auth;
    @Autowired private ProjectRepository projects;
    @Autowired private TestRunRepository testRuns;

    @Test
    void observabilityRequiresProjectReadAccess() throws Exception {
        AuthResponse owner = auth.register(new RegisterRequest(
                "Metrics Owner", "metrics-owner@testpilot.com", "password"));
        AuthResponse intruder = auth.register(new RegisterRequest(
                "Metrics Intruder", "metrics-intruder@testpilot.com", "password"));
        Project project = projects.save(new Project("Private metrics", "observed", owner.userId()));
        TestRun run = testRuns.save(new TestRun(project.getId()));
        String endpoint = "/api/test-runs/" + run.getId() + "/observability";

        mockMvc.perform(get(endpoint).header("Authorization", "Bearer " + intruder.token()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get(endpoint).header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testRunId").value(run.getId()))
                .andExpect(jsonPath("$.rag.traceCount").value(0))
                .andExpect(jsonPath("$.models.invocationCount").value(0));
    }
}

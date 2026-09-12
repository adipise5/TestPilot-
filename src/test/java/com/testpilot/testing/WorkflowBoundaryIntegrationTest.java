package com.testpilot.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.auth.dto.AuthResponse;
import com.testpilot.auth.dto.RegisterRequest;
import com.testpilot.auth.service.AuthService;
import com.testpilot.project.dto.CreateCodeFileRequest;
import com.testpilot.project.dto.CreateProjectRequest;
import com.testpilot.testing.dto.TestRunResponse;
import com.testpilot.testing.service.TestRunService;
import com.testpilot.testing.orchestrator.TestRunOrchestrator;
import com.testpilot.testing.workflow.dto.WorkflowDecisionRequest;
import com.testpilot.testing.workflow.dto.WorkflowToolRequest;
import com.testpilot.testing.workflow.repository.WorkflowRunRepository;
import com.testpilot.testing.workflow.repository.WorkflowStepExecutionRepository;
import com.testpilot.testing.workflow.service.WorkflowRunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class WorkflowBoundaryIntegrationTest {

    private static final String INTERNAL_TOKEN = "test-only-workflow-token-at-least-32-bytes";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AuthService authService;
    @Autowired private TestRunService testRunService;
    @Autowired private WorkflowRunService workflowRunService;
    @Autowired private WorkflowStepExecutionRepository stepRepository;
    @Autowired private WorkflowRunRepository workflowRepository;
    @MockBean private TestRunOrchestrator orchestrator;

    private AuthResponse owner;
    private AuthResponse attacker;
    private Long projectId;
    private TestRunResponse testRun;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = java.util.UUID.randomUUID().toString();
        owner = authService.register(new RegisterRequest(
                "Workflow Owner", "workflow-owner-" + suffix + "@testpilot.com", "password"));
        attacker = authService.register(new RegisterRequest(
                "Workflow Attacker", "workflow-attacker-" + suffix + "@testpilot.com", "password"));

        String projectJson = mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateProjectRequest(
                                "Workflow project", "Tool boundary test"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        projectId = objectMapper.readTree(projectJson).path("id").asLong();

        mockMvc.perform(post("/api/projects/" + projectId + "/files")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCodeFileRequest(
                                "Calculator.java",
                                "src/main/java/example/Calculator.java",
                                "package example; public class Calculator { int add(int a, int b) { return a + b; } }"))))
                .andExpect(status().isCreated());

        testRun = testRunService.createTestRun(projectId, principal(owner));
        workflowRunService.create(testRun.id(), projectId);
    }

    @Test
    void internalToolRequiresSecretAndReplaysCompletedStepWithoutRepeatingIt() throws Exception {
        var workflow = workflowRunService.requireByTestRun(testRun.id());
        JsonNode state = objectMapper.createObjectNode()
                .put("test_run_id", testRun.id())
                .put("project_id", projectId)
                .put("thread_id", workflow.getThreadId())
                .put("graph_version", workflow.getGraphVersion());
        WorkflowToolRequest request = new WorkflowToolRequest(
                1,
                testRun.id(),
                workflow.getThreadId(),
                workflow.getGraphVersion(),
                workflow.getThreadId() + ":intake:v1",
                state);
        String payload = objectMapper.writeValueAsString(request);

        mockMvc.perform(post("/api/internal/v1/workflow-tools/intake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/internal/v1/workflow-tools/intake")
                        .header("X-TestPilot-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.updates.commit_sha").isNotEmpty());

        mockMvc.perform(post("/api/internal/v1/workflow-tools/intake")
                        .header("X-TestPilot-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        var updatedWorkflow = workflowRunService.requireByTestRun(testRun.id());
        org.junit.jupiter.api.Assertions.assertEquals(
                1,
                stepRepository.findByWorkflowRunIdOrderByStartedAtAsc(updatedWorkflow.getId()).size());
    }

    @Test
    void workflowTraceIsProtectedByProjectOwnership() throws Exception {
        mockMvc.perform(get("/api/test-runs/" + testRun.id() + "/workflow")
                        .header("Authorization", "Bearer " + attacker.token()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/test-runs/" + testRun.id() + "/workflow")
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.threadId").value("test-run-" + testRun.id()))
                .andExpect(jsonPath("$.steps", hasSize(0)));
    }

    @Test
    void onlyProjectOwnerCanApproveAndResumeAWaitingWorkflow() throws Exception {
        var workflow = workflowRunService.requireByTestRun(testRun.id());
        workflow.requestApproval("Approve controlled database fixture");
        workflowRepository.save(workflow);
        WorkflowDecisionRequest decision = new WorkflowDecisionRequest(true, "Approved test fixture");

        mockMvc.perform(post("/api/test-runs/" + testRun.id() + "/workflow/decision")
                        .header("Authorization", "Bearer " + attacker.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(decision)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/test-runs/" + testRun.id() + "/workflow/decision")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(decision)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.approvalDecision").value("APPROVED"));

        verify(orchestrator).resumeTestRunAsync(testRun.id(), true, "Approved test fixture");
    }

    private com.testpilot.auth.security.UserPrincipal principal(AuthResponse response) {
        return new com.testpilot.auth.security.UserPrincipal(
                response.userId(),
                response.name(),
                response.email(),
                "",
                response.role(),
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "ROLE_" + response.role().name())));
    }
}

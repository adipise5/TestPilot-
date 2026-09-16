package com.testpilot.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.auth.dto.AuthResponse;
import com.testpilot.auth.dto.RegisterRequest;
import com.testpilot.auth.service.AuthService;
import com.testpilot.delivery.github.GitHubDeliveryGateway;
import com.testpilot.delivery.github.GitHubDeliveryRequest;
import com.testpilot.delivery.github.GitHubDeliveryResult;
import com.testpilot.project.entity.Project;
import com.testpilot.project.repository.ProjectRepository;
import com.testpilot.rag.entity.RagRetrievalTrace;
import com.testpilot.rag.repository.RagRetrievalTraceRepository;
import com.testpilot.repository.connector.RepositoryTransport;
import com.testpilot.repository.entity.*;
import com.testpilot.repository.repository.*;
import com.testpilot.testing.entity.*;
import com.testpilot.testing.execution.TestExecutionMetrics;
import com.testpilot.testing.execution.TestExecutionOutcome;
import com.testpilot.testing.execution.TestExecutionOutcomeType;
import com.testpilot.testing.execution.job.ExecutionJobPersistenceService;
import com.testpilot.testing.repository.GeneratedTestRepository;
import com.testpilot.testing.repository.TestResultRepository;
import com.testpilot.testing.repository.TestRunRepository;
import com.testpilot.testing.workflow.entity.WorkflowRun;
import com.testpilot.testing.workflow.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DeliveryIntegrationTest {

    private static final String BASE_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HEAD_SHA = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AuthService auth;
    @Autowired private ProjectRepository projects;
    @Autowired private ConnectedRepositoryRepository connectedRepositories;
    @Autowired private GitHubInstallationGrantRepository grants;
    @Autowired private RepositoryIngestionRepository ingestions;
    @Autowired private RepositoryArtifactRepository artifacts;
    @Autowired private TestRunRepository testRuns;
    @Autowired private WorkflowRunRepository workflows;
    @Autowired private GeneratedTestRepository generatedTests;
    @Autowired private TestResultRepository testResults;
    @Autowired private ExecutionJobPersistenceService jobs;
    @Autowired private RagRetrievalTraceRepository ragTraces;

    @MockBean private GitHubDeliveryGateway github;

    private String ownerToken;
    private String intruderToken;
    private Long ownerId;
    private TestRun testRun;
    private ConnectedRepository repository;

    @BeforeEach
    void setUp() {
        AuthResponse owner = auth.register(new RegisterRequest(
                "Delivery Owner", "delivery-owner@testpilot.com", "password"));
        AuthResponse intruder = auth.register(new RegisterRequest(
                "Delivery Intruder", "delivery-intruder@testpilot.com", "password"));
        ownerToken = "Bearer " + owner.token();
        intruderToken = "Bearer " + intruder.token();
        ownerId = owner.userId();

        Project project = projects.save(new Project("Delivery project", "phase eight", ownerId));
        repository = connectedRepositories.save(new ConnectedRepository(
                project.getId(), RepositoryTransport.GITHUB_APP_REST, "octocat", "sample", "main",
                BASE_SHA, 42L, "installation:42:repository:octocat/sample"));
        grants.save(new GitHubInstallationGrant(ownerId, 42L));
        RepositoryIngestion ingestion = new RepositoryIngestion(repository.getId(), BASE_SHA);
        ingestion.complete(BuildSystem.MAVEN, 1, "c".repeat(64));
        ingestion = ingestions.save(ingestion);
        artifacts.save(new RepositoryArtifact(
                ingestion.getId(), "pom.xml", "d".repeat(40), "e".repeat(64),
                RepositoryArtifactKind.BUILD_MANIFEST, 20, "<project/>") );

        testRun = testRuns.save(new TestRun(project.getId()));
        testRun.recordExecutionOutcome(TestExecutionOutcomeType.SUCCESS, 0, "BUILD SUCCESS");
        testRun.setStatus(TestRunStatus.COMPLETED);
        testRun = testRuns.save(testRun);
        WorkflowRun workflow = new WorkflowRun(
                testRun.getId(), project.getId(), "delivery-run-" + testRun.getId(), "testpilot-v1");
        workflow.recordCommit(BASE_SHA);
        workflow.complete("{\"status\":\"COMPLETED\"}", false);
        workflows.save(workflow);

        RagRetrievalTrace trace = ragTraces.save(new RagRetrievalTrace(
                testRun.getId(), ownerId, project.getId(), BASE_SHA, "f".repeat(64),
                "Calculator boundaries", "context", 2, 5, 3, 2, 1, "mock-hash-v1", 0,
                "hybrid-rrf-v1", """
                [{"chunkId":1,"chunkKey":"guide#divide","uri":"repo://octocat/sample/guides/unit.md","source":"guides/unit.md","symbol":"divide","startLine":1,"endLine":4,"contentHash":"abc","score":0.9}]
                """));
        generatedTests.save(new GeneratedTest(
                testRun.getId(), "Calculator.java", "com.example.CalculatorGeneratedTest", """
                package com.example;
                import org.junit.jupiter.api.Test;
                class CalculatorGeneratedTest {
                    @Test
                    void adds() { org.junit.jupiter.api.Assertions.assertEquals(2, 1 + 1); }
                }
                """, TestLevel.UNIT, trace.getId()));
        testResults.save(new TestResult(
                testRun.getId(), "com.example.CalculatorGeneratedTest.adds", TestResultStatus.PASSED,
                null, null, 0.01));

        var job = jobs.submit(testRun.getId());
        assertTrue(jobs.claim(job.getId(), "delivery-worker"));
        jobs.complete(job.getId(), "delivery-worker", new TestExecutionOutcome(
                TestExecutionOutcomeType.SUCCESS,
                List.of(),
                0,
                "BUILD SUCCESS\nTests run: 1, Failures: 0",
                "container",
                new TestExecutionMetrics(91.0, 75.0, "COLLECTED", "COLLECTED")));
        when(github.deliver(any())).thenReturn(new GitHubDeliveryResult(
                91L, "https://github.example/octocat/sample/pull/91", HEAD_SHA));
    }

    @Test
    void freezesApprovesAndIdempotentlyDeliversEvidenceBackedPullRequest() throws Exception {
        String endpoint = "/api/test-runs/" + testRun.getId() + "/delivery";

        mockMvc.perform(post(endpoint).header("Authorization", ownerToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.baseCommitSha").value(BASE_SHA))
                .andExpect(jsonPath("$.deliveryBranch").value(
                        org.hamcrest.Matchers.startsWith("testpilot/run-" + testRun.getId() + "-")))
                .andExpect(jsonPath("$.patchText").value(
                        org.hamcrest.Matchers.containsString("--- /dev/null")))
                .andExpect(jsonPath("$.validationResult").value("PASSED"));

        mockMvc.perform(post(endpoint + "/deliver").header("Authorization", ownerToken))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(github);

        mockMvc.perform(post(endpoint + "/decision")
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true,\"comment\":\"Reviewed generated patch\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.reviewerName").value("Delivery Owner"))
                .andExpect(jsonPath("$.reviewerEmail").value("delivery-owner@testpilot.com"));

        mockMvc.perform(post(endpoint + "/deliver").header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.pullRequestNumber").value(91))
                .andExpect(jsonPath("$.headCommitSha").value(HEAD_SHA))
                .andExpect(jsonPath("$.rollbackPath").value(
                        org.hamcrest.Matchers.containsString("default branch `main` is never written")));

        mockMvc.perform(post(endpoint + "/deliver").header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pullRequestNumber").value(91));
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(GitHubDeliveryRequest.class);
        verify(github, times(1)).deliver(requestCaptor.capture());
        GitHubDeliveryRequest request = requestCaptor.getValue();
        assertEquals(BASE_SHA, request.baseCommitSha());
        assertEquals("main", request.baseBranch());
        assertTrue(request.deliveryBranch().startsWith("testpilot/"));
        assertTrue(request.pullRequestBody().contains("guides/unit.md"));
        assertTrue(request.pullRequestBody().contains("BUILD SUCCESS"));
        assertTrue(request.pullRequestBody().contains("system and browser end-to-end testing are outside"));
        assertTrue(request.pullRequestBody().contains("Coverage change: `not collected`"));
    }

    @Test
    void rejectsCrossProjectAccessAndMcpWrites() throws Exception {
        String endpoint = "/api/test-runs/" + testRun.getId() + "/delivery";
        mockMvc.perform(post(endpoint).header("Authorization", intruderToken))
                .andExpect(status().isForbidden());

        repository.reconnect(
                RepositoryTransport.GITHUB_MCP, repository.getOwner(), repository.getName(), "main",
                BASE_SHA, null, "mcp-allowlist:repository:octocat/sample");
        connectedRepositories.save(repository);
        mockMvc.perform(post(endpoint).header("Authorization", ownerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("MCP remains read-only")));
        verifyNoInteractions(github);
    }
}

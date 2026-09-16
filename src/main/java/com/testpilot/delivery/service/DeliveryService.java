package com.testpilot.delivery.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.auth.security.UserPrincipal;
import com.testpilot.common.exception.ExternalServiceException;
import com.testpilot.common.exception.InvalidRequestException;
import com.testpilot.common.exception.ResourceNotFoundException;
import com.testpilot.delivery.dto.DeliveryDecisionRequest;
import com.testpilot.delivery.dto.DeliveryResponse;
import com.testpilot.delivery.entity.DeliveryRecord;
import com.testpilot.delivery.entity.DeliveryStatus;
import com.testpilot.delivery.github.*;
import com.testpilot.delivery.repository.DeliveryRecordRepository;
import com.testpilot.project.service.ProjectAuthorizationService;
import com.testpilot.rag.repository.RagRetrievalTraceRepository;
import com.testpilot.repository.connector.RepositoryTransport;
import com.testpilot.repository.entity.*;
import com.testpilot.repository.repository.ConnectedRepositoryRepository;
import com.testpilot.repository.repository.RepositoryArtifactRepository;
import com.testpilot.repository.repository.RepositoryIngestionRepository;
import com.testpilot.repository.service.GitHubInstallationService;
import com.testpilot.repository.service.RepositoryAuditService;
import com.testpilot.testing.entity.GeneratedTest;
import com.testpilot.testing.entity.TestResult;
import com.testpilot.testing.entity.TestResultStatus;
import com.testpilot.testing.entity.TestRun;
import com.testpilot.testing.entity.TestRunStatus;
import com.testpilot.testing.execution.TestExecutionOutcomeType;
import com.testpilot.testing.execution.job.ExecutionJob;
import com.testpilot.testing.execution.job.ExecutionJobPersistenceService;
import com.testpilot.testing.execution.job.ExecutionJobStatus;
import com.testpilot.testing.repository.GeneratedTestRepository;
import com.testpilot.testing.repository.TestResultRepository;
import com.testpilot.testing.repository.TestRunRepository;
import com.testpilot.testing.workflow.entity.WorkflowStatus;
import com.testpilot.testing.workflow.repository.WorkflowRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DeliveryService {

    private final DeliveryRecordRepository deliveries;
    private final TestRunRepository testRuns;
    private final GeneratedTestRepository generatedTests;
    private final TestResultRepository testResults;
    private final WorkflowRunRepository workflows;
    private final ConnectedRepositoryRepository connectedRepositories;
    private final RepositoryIngestionRepository ingestions;
    private final RepositoryArtifactRepository artifacts;
    private final ExecutionJobPersistenceService executionJobs;
    private final RagRetrievalTraceRepository ragTraces;
    private final ProjectAuthorizationService authorization;
    private final GitHubInstallationService installations;
    private final RepositoryAuditService audits;
    private final DeliveryPatchBuilder patchBuilder;
    private final DeliveryEvidenceBuilder evidenceBuilder;
    private final GitBranchPolicy branchPolicy;
    private final DeliveryPersistenceService persistence;
    private final GitHubDeliveryGateway github;
    private final ObjectMapper objectMapper;

    public DeliveryService(
            DeliveryRecordRepository deliveries,
            TestRunRepository testRuns,
            GeneratedTestRepository generatedTests,
            TestResultRepository testResults,
            WorkflowRunRepository workflows,
            ConnectedRepositoryRepository connectedRepositories,
            RepositoryIngestionRepository ingestions,
            RepositoryArtifactRepository artifacts,
            ExecutionJobPersistenceService executionJobs,
            RagRetrievalTraceRepository ragTraces,
            ProjectAuthorizationService authorization,
            GitHubInstallationService installations,
            RepositoryAuditService audits,
            DeliveryPatchBuilder patchBuilder,
            DeliveryEvidenceBuilder evidenceBuilder,
            GitBranchPolicy branchPolicy,
            DeliveryPersistenceService persistence,
            GitHubDeliveryGateway github,
            ObjectMapper objectMapper) {
        this.deliveries = deliveries;
        this.testRuns = testRuns;
        this.generatedTests = generatedTests;
        this.testResults = testResults;
        this.workflows = workflows;
        this.connectedRepositories = connectedRepositories;
        this.ingestions = ingestions;
        this.artifacts = artifacts;
        this.executionJobs = executionJobs;
        this.ragTraces = ragTraces;
        this.authorization = authorization;
        this.installations = installations;
        this.audits = audits;
        this.patchBuilder = patchBuilder;
        this.evidenceBuilder = evidenceBuilder;
        this.branchPolicy = branchPolicy;
        this.persistence = persistence;
        this.github = github;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public DeliveryResponse createProposal(Long testRunId, UserPrincipal currentUser) {
        TestRun testRun = requireRun(testRunId);
        authorization.requireWriteAccess(testRun.getProjectId(), currentUser);
        var existing = deliveries.findByTestRunId(testRunId);
        if (existing.isPresent()) return DeliveryResponse.fromEntity(existing.get());

        requireSuccessfulTestRun(testRun);
        var workflow = workflows.findByTestRunId(testRunId)
                .orElseThrow(() -> new InvalidRequestException("Delivery requires a completed agent workflow"));
        if (workflow.getStatus() != WorkflowStatus.COMPLETED) {
            throw new InvalidRequestException("Delivery requires a completed agent workflow");
        }
        String baseCommitSha = requireCommitSha(workflow.getCommitSha());

        ConnectedRepository repository = connectedRepositories.findByProjectId(testRun.getProjectId())
                .orElseThrow(() -> new InvalidRequestException("Delivery requires a connected GitHub repository"));
        requireDeliveryConnection(repository, currentUser);
        RepositoryIngestion ingestion = ingestions
                .findByConnectedRepositoryIdAndCommitSha(repository.getId(), baseCommitSha)
                .filter(item -> item.getStatus() == RepositoryIngestionStatus.COMPLETED)
                .orElseThrow(() -> new InvalidRequestException(
                        "The analyzed commit does not have a completed immutable repository catalog"));

        ExecutionJob job = executionJobs.findByTestRunId(testRunId)
                .orElseThrow(() -> new InvalidRequestException("Delivery requires isolated execution evidence"));
        requireSuccessfulExecution(job);
        List<TestResult> results = testResults.findByTestRunId(testRunId);
        requirePassingResults(results);
        List<GeneratedTest> tests = generatedTests.findByTestRunId(testRunId).stream()
                .sorted(java.util.Comparator.comparing(GeneratedTest::getTestClass))
                .toList();
        Set<String> basePaths = artifacts.findByIngestionIdOrderByPath(ingestion.getId()).stream()
                .map(RepositoryArtifact::getPath)
                .collect(Collectors.toSet());
        DeliveryPatchBuilder.PreparedPatch prepared = patchBuilder.prepare(tests, basePaths);

        String baseBranch = branchPolicy.requireSafeBaseBranch(repository.getDefaultBranch());
        String deliveryBranch = branchPolicy.requireDeliveryBranch(
                "testpilot/run-" + testRunId + "-" + prepared.patchSha256().substring(0, 10), baseBranch);
        String rollbackPath = "Close the pull request without merging and delete branch `" + deliveryBranch
                + "`. The protected/default branch `" + baseBranch + "` is never written by TestPilot.";
        String validationSummary = evidenceBuilder.validationSummary(job, results, tests.size());
        String title = "TestPilot: add generated tests for run #" + testRunId;
        String pullRequestBody = evidenceBuilder.pullRequestBody(
                testRunId, baseCommitSha, prepared.patchSha256(), validationSummary, rollbackPath,
                tests, job, ragTraces.findByTestRunIdOrderByCreatedAtAsc(testRunId));

        DeliveryRecord record = deliveries.save(new DeliveryRecord(
                testRunId,
                testRun.getProjectId(),
                repository.getId(),
                repository.getOwner(),
                repository.getName(),
                repository.getInstallationId(),
                baseCommitSha,
                baseBranch,
                deliveryBranch,
                prepared.patchSha256(),
                prepared.patchText(),
                writeChanges(prepared.changes()),
                title,
                pullRequestBody,
                DeliveryEvidenceBuilder.LIMITATIONS,
                rollbackPath,
                validationSummary,
                currentUser.getId()));
        audit(repository, currentUser.getId(), "CREATE_DELIVERY_PROPOSAL", "SUCCESS",
                "testRun=" + testRunId + ", patch=" + prepared.patchSha256());
        return DeliveryResponse.fromEntity(record);
    }

    @Transactional(readOnly = true)
    public DeliveryResponse get(Long testRunId, UserPrincipal currentUser) {
        TestRun run = requireRun(testRunId);
        authorization.requireReadAccess(run.getProjectId(), currentUser);
        return DeliveryResponse.fromEntity(requireDelivery(testRunId));
    }

    @Transactional
    public DeliveryResponse decide(
            Long testRunId,
            DeliveryDecisionRequest request,
            UserPrincipal currentUser) {
        TestRun run = requireRun(testRunId);
        authorization.requireWriteAccess(run.getProjectId(), currentUser);
        DeliveryRecord record = requireDelivery(testRunId);
        try {
            record.recordDecision(
                    request.approved(), currentUser.getId(), currentUser.getName(),
                    currentUser.getEmail(), request.comment());
        } catch (IllegalStateException e) {
            throw new InvalidRequestException(e.getMessage());
        }
        deliveries.save(record);
        ConnectedRepository repository = requirePinnedConnection(record);
        audit(repository, currentUser.getId(), "REVIEW_DELIVERY_PROPOSAL",
                request.approved() ? "APPROVED" : "REJECTED",
                "testRun=" + testRunId + ", patch=" + record.getPatchSha256());
        return DeliveryResponse.fromEntity(record);
    }

    public DeliveryResponse deliver(Long testRunId, UserPrincipal currentUser) {
        TestRun run = requireRun(testRunId);
        authorization.requireWriteAccess(run.getProjectId(), currentUser);
        DeliveryRecord current = requireDelivery(testRunId);
        if (current.getStatus() == DeliveryStatus.DELIVERED) {
            return DeliveryResponse.fromEntity(current);
        }
        ConnectedRepository repository = requirePinnedConnection(current);
        requireDeliveryConnection(repository, currentUser);

        boolean started = false;
        try {
            DeliveryRecord delivery = persistence.begin(current.getId(), currentUser.getId());
            started = true;
            GitHubDeliveryResult result = github.deliver(new GitHubDeliveryRequest(
                    delivery.getRepositoryOwner(),
                    delivery.getRepositoryName(),
                    delivery.getInstallationId(),
                    delivery.getBaseCommitSha(),
                    delivery.getBaseBranch(),
                    delivery.getDeliveryBranch(),
                    "Add TestPilot generated tests for run #" + testRunId,
                    delivery.getPullRequestTitle(),
                    delivery.getPullRequestBody(),
                    readChanges(delivery.getChangesJson())));
            DeliveryRecord completed = persistence.complete(delivery.getId(), result);
            audit(repository, currentUser.getId(), "DELIVER_PULL_REQUEST", "SUCCESS",
                    "testRun=" + testRunId + ", pr=" + result.pullRequestNumber()
                            + ", head=" + result.headCommitSha());
            return DeliveryResponse.fromEntity(completed);
        } catch (RuntimeException error) {
            if (started) {
                persistence.fail(current.getId(), safeFailure(error));
                audit(repository, currentUser.getId(), "DELIVER_PULL_REQUEST", "FAILED",
                        "testRun=" + testRunId + ", patch=" + current.getPatchSha256());
            }
            throw error;
        }
    }

    private TestRun requireRun(Long testRunId) {
        return testRuns.findById(testRunId)
                .orElseThrow(() -> new ResourceNotFoundException("TestRun not found: " + testRunId));
    }

    private DeliveryRecord requireDelivery(Long testRunId) {
        return deliveries.findByTestRunId(testRunId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Delivery proposal not found for TestRun: " + testRunId));
    }

    private void requireSuccessfulTestRun(TestRun run) {
        if (run.getStatus() != TestRunStatus.COMPLETED
                || run.getExecutionOutcome() != TestExecutionOutcomeType.SUCCESS) {
            throw new InvalidRequestException("Only a successfully completed TestRun can be delivered");
        }
    }

    private void requireSuccessfulExecution(ExecutionJob job) {
        if (job.getStatus() != ExecutionJobStatus.COMPLETED
                || job.getOutcome() != TestExecutionOutcomeType.SUCCESS) {
            throw new InvalidRequestException("Delivery requires a successful terminal execution job");
        }
    }

    private void requirePassingResults(List<TestResult> results) {
        boolean hasPassed = results.stream().anyMatch(result -> result.getStatus() == TestResultStatus.PASSED);
        boolean hasFailure = results.stream().anyMatch(result ->
                result.getStatus() == TestResultStatus.FAILED || result.getStatus() == TestResultStatus.ERROR);
        if (!hasPassed || hasFailure) {
            throw new InvalidRequestException("Delivery requires at least one passing test and no failed tests");
        }
    }

    private void requireDeliveryConnection(ConnectedRepository repository, UserPrincipal currentUser) {
        if (repository.getStatus() != RepositoryConnectionStatus.CONNECTED
                || repository.getTransport() != RepositoryTransport.GITHUB_APP_REST
                || repository.getInstallationId() == null) {
            throw new InvalidRequestException(
                    "Repository writes require an active GitHub App connection; MCP remains read-only");
        }
        installations.requireGrant(currentUser.getId(), repository.getInstallationId());
    }

    private ConnectedRepository requirePinnedConnection(DeliveryRecord delivery) {
        ConnectedRepository repository = connectedRepositories.findById(delivery.getConnectedRepositoryId())
                .orElseThrow(() -> new InvalidRequestException("The delivery repository no longer exists"));
        boolean matches = repository.getOwner().equalsIgnoreCase(delivery.getRepositoryOwner())
                && repository.getName().equalsIgnoreCase(delivery.getRepositoryName())
                && java.util.Objects.equals(repository.getInstallationId(), delivery.getInstallationId());
        if (!matches) {
            throw new InvalidRequestException(
                    "The connected repository changed after proposal creation; delivery is blocked");
        }
        return repository;
    }

    private String requireCommitSha(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{40}")) {
            throw new InvalidRequestException("Workflow does not reference a full immutable commit SHA");
        }
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    private String writeChanges(List<DeliveryChange> changes) {
        try {
            return objectMapper.writeValueAsString(changes);
        } catch (Exception e) {
            throw new IllegalStateException("Delivery changes could not be serialized", e);
        }
    }

    private List<DeliveryChange> readChanges(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<List<DeliveryChange>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Stored delivery changes could not be decoded", e);
        }
    }

    private void audit(
            ConnectedRepository repository,
            Long actor,
            String action,
            String outcome,
            String detail) {
        audits.record(repository.getId(), actor, repository.getInstallationId(), repository.getOwner(),
                repository.getName(), action, outcome, detail);
    }

    private String safeFailure(RuntimeException error) {
        if (error instanceof InvalidRequestException || error instanceof ExternalServiceException) {
            return error.getMessage();
        }
        return "GitHub delivery failed; inspect repository audit evidence before retrying";
    }
}

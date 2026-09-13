package com.testpilot.testing.execution;

import com.testpilot.common.exception.ResourceNotFoundException;
import com.testpilot.project.entity.CodeFile;
import com.testpilot.project.service.ProjectSourceService;
import com.testpilot.repository.entity.RepositoryArtifact;
import com.testpilot.repository.entity.RepositoryConnectionStatus;
import com.testpilot.repository.entity.RepositoryIngestionStatus;
import com.testpilot.repository.repository.ConnectedRepositoryRepository;
import com.testpilot.repository.repository.RepositoryArtifactRepository;
import com.testpilot.repository.repository.RepositoryIngestionRepository;
import com.testpilot.testing.entity.GeneratedTest;
import com.testpilot.testing.entity.TestResult;
import com.testpilot.testing.entity.TestRun;
import com.testpilot.testing.entity.TestRunStatus;
import com.testpilot.testing.execution.job.ExecutionJobCoordinator;
import com.testpilot.testing.execution.job.ExecutionJobPersistenceService;
import com.testpilot.testing.repository.GeneratedTestRepository;
import com.testpilot.testing.repository.TestResultRepository;
import com.testpilot.testing.repository.TestRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DurableTestExecutionService {

    private final TestRunRepository testRunRepository;
    private final GeneratedTestRepository generatedTestRepository;
    private final TestResultRepository testResultRepository;
    private final ProjectSourceService projectSourceService;
    private final ExecutionJobCoordinator coordinator;
    private final ExecutionJobPersistenceService jobs;
    private final ConnectedRepositoryRepository connectedRepositories;
    private final RepositoryIngestionRepository ingestions;
    private final RepositoryArtifactRepository artifacts;

    public DurableTestExecutionService(
            TestRunRepository testRunRepository,
            GeneratedTestRepository generatedTestRepository,
            TestResultRepository testResultRepository,
            ProjectSourceService projectSourceService,
            ExecutionJobCoordinator coordinator,
            ExecutionJobPersistenceService jobs,
            ConnectedRepositoryRepository connectedRepositories,
            RepositoryIngestionRepository ingestions,
            RepositoryArtifactRepository artifacts) {
        this.testRunRepository = testRunRepository;
        this.generatedTestRepository = generatedTestRepository;
        this.testResultRepository = testResultRepository;
        this.projectSourceService = projectSourceService;
        this.coordinator = coordinator;
        this.jobs = jobs;
        this.connectedRepositories = connectedRepositories;
        this.ingestions = ingestions;
        this.artifacts = artifacts;
    }

    public TestExecutionOutcome execute(Long testRunId) {
        TestRun testRun = requireRun(testRunId);
        List<TestResult> existingResults = testResultRepository.findByTestRunId(testRunId);
        if (testRun.getExecutionOutcome() != null) {
            return jobs.findByTestRunId(testRunId)
                    .filter(job -> job.getOutcome() != null)
                    .map(jobs::readOutcome)
                    .map(outcome -> withResults(outcome, existingResults))
                    .orElseGet(() -> new TestExecutionOutcome(
                            testRun.getExecutionOutcome(), existingResults,
                            testRun.getProcessExitCode(), testRun.getExecutionOutput()));
        }

        List<CodeFile> sourceFiles = projectSourceService.getActiveSourceFiles(testRun.getProjectId());
        List<GeneratedTest> generatedTests = generatedTestRepository.findByTestRunId(testRunId);
        List<RepositoryArtifact> catalog = immutableCatalog(testRun.getProjectId());
        TestExecutionOutcome outcome = catalog.isEmpty()
                ? coordinator.execute(testRunId, sourceFiles, generatedTests)
                : coordinator.executeCatalog(testRunId, catalog, generatedTests);
        return persistOutcome(testRunId, outcome);
    }

    @Transactional
    public TestExecutionOutcome persistOutcome(Long testRunId, TestExecutionOutcome outcome) {
        TestRun testRun = requireRun(testRunId);
        List<TestResult> existing = testResultRepository.findByTestRunId(testRunId);
        List<TestResult> results = existing.isEmpty()
                ? testResultRepository.saveAll(outcome.results())
                : existing;
        if (testRun.getExecutionOutcome() == null) {
            testRun.recordExecutionOutcome(outcome.type(), outcome.processExitCode(), outcome.output());
            testRunRepository.save(testRun);
        }
        return withResults(outcome, results);
    }

    public void recoverJob(Long jobId) {
        var job = jobs.get(jobId);
        TestExecutionOutcome outcome = execute(job.getTestRunId());
        TestRun run = requireRun(job.getTestRunId());
        if (run.getStatus() != TestRunStatus.COMPLETED
                && run.getStatus() != TestRunStatus.FAILED
                && run.getStatus() != TestRunStatus.REJECTED) {
            run.setStatus(outcome.completedTestProcess() ? TestRunStatus.COMPLETED : TestRunStatus.FAILED);
            testRunRepository.save(run);
        }
    }

    private TestExecutionOutcome withResults(TestExecutionOutcome outcome, List<TestResult> results) {
        return new TestExecutionOutcome(
                outcome.type(), results, outcome.processExitCode(), outcome.output(),
                outcome.isolationBackend(), outcome.metrics());
    }

    private TestRun requireRun(Long testRunId) {
        return testRunRepository.findById(testRunId)
                .orElseThrow(() -> new ResourceNotFoundException("TestRun not found: " + testRunId));
    }

    private List<RepositoryArtifact> immutableCatalog(Long projectId) {
        return connectedRepositories.findByProjectId(projectId)
                .filter(repository -> repository.getStatus() == RepositoryConnectionStatus.CONNECTED)
                .flatMap(repository -> ingestions.findByConnectedRepositoryIdAndCommitSha(
                        repository.getId(), repository.getSelectedCommitSha()))
                .filter(ingestion -> ingestion.getStatus() == RepositoryIngestionStatus.COMPLETED)
                .map(ingestion -> artifacts.findByIngestionIdOrderByPath(ingestion.getId()))
                .orElse(List.of());
    }
}

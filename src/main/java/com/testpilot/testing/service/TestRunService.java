package com.testpilot.testing.service;

import com.testpilot.auth.security.UserPrincipal;
import com.testpilot.common.exception.ResourceNotFoundException;
import com.testpilot.common.validation.RepositoryPathPolicy;
import com.testpilot.project.service.ProjectService;
import com.testpilot.testing.dto.*;
import com.testpilot.testing.entity.*;
import com.testpilot.testing.execution.DurableTestExecutionService;
import com.testpilot.testing.execution.TestExecutionOutcome;
import com.testpilot.testing.execution.TestExecutionOutcomeType;
import com.testpilot.testing.execution.job.ExecutionJobPersistenceService;
import com.testpilot.testing.execution.job.ExecutionJobResponse;
import com.testpilot.testing.repository.GeneratedTestRepository;
import com.testpilot.testing.repository.TestResultRepository;
import com.testpilot.testing.repository.TestRunRepository;
import com.testpilot.testing.validation.GeneratedTestPolicyValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class TestRunService {

    private final TestRunRepository testRunRepository;
    private final GeneratedTestRepository generatedTestRepository;
    private final TestResultRepository testResultRepository;
    private final ProjectService projectService;
    private final DurableTestExecutionService testExecutionService;
    private final ExecutionJobPersistenceService executionJobs;
    private final RepositoryPathPolicy repositoryPathPolicy;
    private final GeneratedTestPolicyValidator testPolicyValidator;

    public TestRunService(
            TestRunRepository testRunRepository,
            GeneratedTestRepository generatedTestRepository,
            TestResultRepository testResultRepository,
            ProjectService projectService,
            DurableTestExecutionService testExecutionService,
            ExecutionJobPersistenceService executionJobs,
            RepositoryPathPolicy repositoryPathPolicy,
            GeneratedTestPolicyValidator testPolicyValidator) {
        this.testRunRepository = testRunRepository;
        this.generatedTestRepository = generatedTestRepository;
        this.testResultRepository = testResultRepository;
        this.projectService = projectService;
        this.testExecutionService = testExecutionService;
        this.executionJobs = executionJobs;
        this.repositoryPathPolicy = repositoryPathPolicy;
        this.testPolicyValidator = testPolicyValidator;
    }

    @Transactional
    public TestRunResponse createTestRun(Long projectId, UserPrincipal currentUser) {
        projectService.findProjectAndVerifyReadAccess(projectId, currentUser);
        TestRun testRun = new TestRun(projectId);
        TestRun saved = testRunRepository.save(testRun);
        return TestRunResponse.fromEntity(saved, List.of(), List.of());
    }

    @Transactional
    public GeneratedTestResponse saveGeneratedTest(Long testRunId, SaveGeneratedTestRequest request, UserPrincipal currentUser) {
        TestRun testRun = findTestRun(testRunId);
        projectService.findProjectAndVerifyWriteAccess(testRun.getProjectId(), currentUser);

        String validatedTestClass = repositoryPathPolicy.validateGeneratedTestClass(request.testClass());
        testPolicyValidator.validate(validatedTestClass, request.testCode(), TestLevel.UNIT);
        GeneratedTest generatedTest = new GeneratedTest(
                testRunId,
                request.sourceFile(),
                validatedTestClass,
                request.testCode()
        );

        GeneratedTest saved = generatedTestRepository.save(generatedTest);
        return GeneratedTestResponse.fromEntity(saved);
    }

    public TestRunResponse executeTestRun(Long testRunId, UserPrincipal currentUser) {
        TestRun testRun = findTestRun(testRunId);
        projectService.findProjectAndVerifyWriteAccess(testRun.getProjectId(), currentUser);

        testRun.setStatus(TestRunStatus.RUNNING_TESTS);
        testRunRepository.save(testRun);

        List<GeneratedTest> generatedTests = generatedTestRepository.findByTestRunId(testRunId);

        TestExecutionOutcome outcome = testExecutionService.execute(testRunId);
        List<TestResult> savedResults = outcome.results();

        testRun = findTestRun(testRunId);
        testRun.recordExecutionOutcome(outcome.type(), outcome.processExitCode(), outcome.output());
        testRun.setStatus(outcome.completedTestProcess() ? TestRunStatus.COMPLETED : TestRunStatus.FAILED);
        TestRun updatedRun = testRunRepository.save(testRun);

        List<GeneratedTestResponse> genDtos = generatedTests.stream().map(GeneratedTestResponse::fromEntity).toList();
        List<TestResultResponse> resDtos = savedResults.stream().map(TestResultResponse::fromEntity).toList();
        return TestRunResponse.fromEntity(updatedRun, jobResponse(testRunId), genDtos, resDtos);
    }

    public TestRunResponse cancelExecution(Long testRunId, UserPrincipal currentUser) {
        TestRun testRun = findTestRun(testRunId);
        projectService.findProjectAndVerifyWriteAccess(testRun.getProjectId(), currentUser);
        var job = executionJobs.requestCancellation(testRunId);
        if (job.getStatus() == com.testpilot.testing.execution.job.ExecutionJobStatus.CANCELLED
                && testRun.getExecutionOutcome() == null) {
            testRun.recordExecutionOutcome(TestExecutionOutcomeType.CANCELLED, null, "Execution cancelled before lease acquisition");
            testRun.setStatus(TestRunStatus.FAILED);
            testRunRepository.save(testRun);
        }
        return getTestRun(testRunId, currentUser);
    }

    @Transactional(readOnly = true)
    public TestRunResponse getTestRun(Long testRunId, UserPrincipal currentUser) {
        TestRun testRun = findTestRun(testRunId);
        projectService.findProjectAndVerifyReadAccess(testRun.getProjectId(), currentUser);

        List<GeneratedTestResponse> genDtos = generatedTestRepository.findByTestRunId(testRunId)
                .stream().map(GeneratedTestResponse::fromEntity).toList();

        List<TestResultResponse> resDtos = testResultRepository.findByTestRunId(testRunId)
                .stream().map(TestResultResponse::fromEntity).toList();

        return TestRunResponse.fromEntity(testRun, jobResponse(testRunId), genDtos, resDtos);
    }

    @Transactional(readOnly = true)
    public List<TestRunResponse> getTestRunsByProject(Long projectId, UserPrincipal currentUser) {
        projectService.findProjectAndVerifyReadAccess(projectId, currentUser);

        return testRunRepository.findByProjectId(projectId).stream()
                .map(run -> {
                    List<GeneratedTestResponse> genDtos = generatedTestRepository.findByTestRunId(run.getId())
                            .stream().map(GeneratedTestResponse::fromEntity).toList();
                    List<TestResultResponse> resDtos = testResultRepository.findByTestRunId(run.getId())
                            .stream().map(TestResultResponse::fromEntity).toList();
                    return TestRunResponse.fromEntity(run, jobResponse(run.getId()), genDtos, resDtos);
                })
                .toList();
    }

    private TestRun findTestRun(Long testRunId) {
        return testRunRepository.findById(testRunId)
                .orElseThrow(() -> new ResourceNotFoundException("TestRun not found with id: " + testRunId));
    }

    private ExecutionJobResponse jobResponse(Long testRunId) {
        return executionJobs.findByTestRunId(testRunId)
                .map(ExecutionJobResponse::from)
                .orElse(null);
    }
}

package com.testpilot.testing.service;

import com.testpilot.auth.security.UserPrincipal;
import com.testpilot.common.exception.ResourceNotFoundException;
import com.testpilot.common.validation.RepositoryPathPolicy;
import com.testpilot.project.entity.CodeFile;
import com.testpilot.project.service.ProjectSourceService;
import com.testpilot.project.service.ProjectService;
import com.testpilot.testing.dto.*;
import com.testpilot.testing.entity.*;
import com.testpilot.testing.execution.TestExecutionService;
import com.testpilot.testing.execution.TestExecutionOutcome;
import com.testpilot.testing.repository.GeneratedTestRepository;
import com.testpilot.testing.repository.TestResultRepository;
import com.testpilot.testing.repository.TestRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class TestRunService {

    private final TestRunRepository testRunRepository;
    private final GeneratedTestRepository generatedTestRepository;
    private final TestResultRepository testResultRepository;
    private final ProjectSourceService projectSourceService;
    private final ProjectService projectService;
    private final TestExecutionService testExecutionService;
    private final RepositoryPathPolicy repositoryPathPolicy;

    public TestRunService(
            TestRunRepository testRunRepository,
            GeneratedTestRepository generatedTestRepository,
            TestResultRepository testResultRepository,
            ProjectSourceService projectSourceService,
            ProjectService projectService,
            TestExecutionService testExecutionService,
            RepositoryPathPolicy repositoryPathPolicy) {
        this.testRunRepository = testRunRepository;
        this.generatedTestRepository = generatedTestRepository;
        this.testResultRepository = testResultRepository;
        this.projectSourceService = projectSourceService;
        this.projectService = projectService;
        this.testExecutionService = testExecutionService;
        this.repositoryPathPolicy = repositoryPathPolicy;
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

        List<CodeFile> sourceFiles = projectSourceService.getActiveSourceFiles(testRun.getProjectId());
        List<GeneratedTest> generatedTests = generatedTestRepository.findByTestRunId(testRunId);

        TestExecutionOutcome outcome = testExecutionService.executeTests(testRunId, sourceFiles, generatedTests);
        List<TestResult> savedResults = testResultRepository.saveAll(outcome.results());

        testRun.recordExecutionOutcome(outcome.type(), outcome.processExitCode(), outcome.output());
        testRun.setStatus(outcome.completedTestProcess() ? TestRunStatus.COMPLETED : TestRunStatus.FAILED);
        TestRun updatedRun = testRunRepository.save(testRun);

        List<GeneratedTestResponse> genDtos = generatedTests.stream().map(GeneratedTestResponse::fromEntity).toList();
        List<TestResultResponse> resDtos = savedResults.stream().map(TestResultResponse::fromEntity).toList();
        return TestRunResponse.fromEntity(updatedRun, genDtos, resDtos);
    }

    @Transactional(readOnly = true)
    public TestRunResponse getTestRun(Long testRunId, UserPrincipal currentUser) {
        TestRun testRun = findTestRun(testRunId);
        projectService.findProjectAndVerifyReadAccess(testRun.getProjectId(), currentUser);

        List<GeneratedTestResponse> genDtos = generatedTestRepository.findByTestRunId(testRunId)
                .stream().map(GeneratedTestResponse::fromEntity).toList();

        List<TestResultResponse> resDtos = testResultRepository.findByTestRunId(testRunId)
                .stream().map(TestResultResponse::fromEntity).toList();

        return TestRunResponse.fromEntity(testRun, genDtos, resDtos);
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
                    return TestRunResponse.fromEntity(run, genDtos, resDtos);
                })
                .toList();
    }

    private TestRun findTestRun(Long testRunId) {
        return testRunRepository.findById(testRunId)
                .orElseThrow(() -> new ResourceNotFoundException("TestRun not found with id: " + testRunId));
    }
}

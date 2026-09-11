package com.testpilot.failure.service;

import com.testpilot.auth.security.UserPrincipal;
import com.testpilot.common.exception.ResourceNotFoundException;
import com.testpilot.failure.agent.FailureAnalysisAgent;
import com.testpilot.failure.agent.FixSuggestionAgent;
import com.testpilot.failure.dto.FailureAnalysisResponse;
import com.testpilot.failure.dto.FixSuggestionResponse;
import com.testpilot.failure.entity.FailureAnalysis;
import com.testpilot.failure.entity.FixStatus;
import com.testpilot.failure.entity.FixSuggestion;
import com.testpilot.failure.repository.FailureAnalysisRepository;
import com.testpilot.failure.repository.FixSuggestionRepository;
import com.testpilot.project.entity.CodeFile;
import com.testpilot.project.service.ProjectSourceService;
import com.testpilot.project.service.ProjectService;
import com.testpilot.rag.service.RagService;
import com.testpilot.testing.entity.GeneratedTest;
import com.testpilot.testing.entity.TestResult;
import com.testpilot.testing.entity.TestRun;
import com.testpilot.testing.repository.GeneratedTestRepository;
import com.testpilot.testing.repository.TestResultRepository;
import com.testpilot.testing.repository.TestRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class FailureService {

    private final FailureAnalysisRepository failureAnalysisRepository;
    private final FixSuggestionRepository fixSuggestionRepository;
    private final TestResultRepository testResultRepository;
    private final TestRunRepository testRunRepository;
    private final GeneratedTestRepository generatedTestRepository;
    private final ProjectSourceService projectSourceService;
    private final ProjectService projectService;
    private final FailureAnalysisAgent failureAnalysisAgent;
    private final FixSuggestionAgent fixSuggestionAgent;
    private final RagService ragService;

    public FailureService(
            FailureAnalysisRepository failureAnalysisRepository,
            FixSuggestionRepository fixSuggestionRepository,
            TestResultRepository testResultRepository,
            TestRunRepository testRunRepository,
            GeneratedTestRepository generatedTestRepository,
            ProjectSourceService projectSourceService,
            ProjectService projectService,
            FailureAnalysisAgent failureAnalysisAgent,
            FixSuggestionAgent fixSuggestionAgent,
            RagService ragService) {
        this.failureAnalysisRepository = failureAnalysisRepository;
        this.fixSuggestionRepository = fixSuggestionRepository;
        this.testResultRepository = testResultRepository;
        this.testRunRepository = testRunRepository;
        this.generatedTestRepository = generatedTestRepository;
        this.projectSourceService = projectSourceService;
        this.projectService = projectService;
        this.failureAnalysisAgent = failureAnalysisAgent;
        this.fixSuggestionAgent = fixSuggestionAgent;
        this.ragService = ragService;
    }

    public FailureAnalysisResponse analyzeFailure(Long testResultId, UserPrincipal currentUser) {
        TestResult testResult = testResultRepository.findById(testResultId)
                .orElseThrow(() -> new ResourceNotFoundException("TestResult not found with id: " + testResultId));

        TestRun testRun = testRunRepository.findById(testResult.getTestRunId())
                .orElseThrow(() -> new ResourceNotFoundException("TestRun not found with id: " + testResult.getTestRunId()));

        projectService.findProjectAndVerifyReadAccess(testRun.getProjectId(), currentUser);

        List<CodeFile> sourceFiles = projectSourceService.getActiveSourceFiles(testRun.getProjectId());
        List<GeneratedTest> generatedTests = generatedTestRepository.findByTestRunId(testRun.getId());

        String sourceCode = sourceFiles.isEmpty() ? "" : sourceFiles.get(0).getContent();
        String testCode = generatedTests.isEmpty() ? "" : generatedTests.get(0).getTestCode();
        String ragContext = ragService.getRelevantContextForTesting(sourceCode);

        // 1. Run Failure Analysis Agent
        FailureAnalysisResponse agentAnalysis = failureAnalysisAgent.analyzeFailure(
                sourceCode,
                testCode,
                testResult.getTestName(),
                testResult.getErrorMessage(),
                testResult.getStackTrace(),
                ragContext
        );

        FailureAnalysis entity = new FailureAnalysis(
                testResultId,
                agentAnalysis.rootCause(),
                agentAnalysis.severity(),
                agentAnalysis.affectedMethod(),
                agentAnalysis.explanation(),
                agentAnalysis.confidence()
        );
        FailureAnalysis savedEntity = failureAnalysisRepository.save(entity);
        FailureAnalysisResponse savedAnalysisResponse = FailureAnalysisResponse.fromEntity(savedEntity);

        // 2. Run Fix Suggestion Agent
        FixSuggestionResponse agentFix = fixSuggestionAgent.generateFix(
                sourceCode,
                savedAnalysisResponse,
                testResult.getStackTrace(),
                ragContext
        );

        FixSuggestion fixEntity = new FixSuggestion(
                savedEntity.getId(),
                agentFix.originalCode(),
                agentFix.suggestedCode(),
                agentFix.explanation()
        );
        fixSuggestionRepository.save(fixEntity);

        return savedAnalysisResponse;
    }

    @Transactional(readOnly = true)
    public FailureAnalysisResponse getFailureAnalysis(Long testResultId, UserPrincipal currentUser) {
        FailureAnalysis analysis = failureAnalysisRepository.findByTestResultId(testResultId)
                .orElseThrow(() -> new ResourceNotFoundException("Failure analysis not found for testResultId: " + testResultId));

        verifyFailureAnalysisAccess(analysis, currentUser, false);

        return FailureAnalysisResponse.fromEntity(analysis);
    }

    @Transactional(readOnly = true)
    public FixSuggestionResponse getFixSuggestion(Long failureAnalysisId, UserPrincipal currentUser) {
        FixSuggestion fix = fixSuggestionRepository.findByFailureAnalysisId(failureAnalysisId)
                .orElseThrow(() -> new ResourceNotFoundException("Fix suggestion not found for failureAnalysisId: " + failureAnalysisId));
        FailureAnalysis analysis = failureAnalysisRepository.findById(failureAnalysisId)
                .orElseThrow(() -> new ResourceNotFoundException("Failure analysis not found with id: " + failureAnalysisId));
        verifyFailureAnalysisAccess(analysis, currentUser, false);
        return FixSuggestionResponse.fromEntity(fix);
    }

    @Transactional
    public FixSuggestionResponse updateFixStatus(Long fixSuggestionId, FixStatus status, UserPrincipal currentUser) {
        FixSuggestion fix = fixSuggestionRepository.findById(fixSuggestionId)
                .orElseThrow(() -> new ResourceNotFoundException("Fix suggestion not found with id: " + fixSuggestionId));

        FailureAnalysis analysis = failureAnalysisRepository.findById(fix.getFailureAnalysisId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Failure analysis not found with id: " + fix.getFailureAnalysisId()));
        verifyFailureAnalysisAccess(analysis, currentUser, true);

        fix.setStatus(status);
        FixSuggestion updated = fixSuggestionRepository.save(fix);
        return FixSuggestionResponse.fromEntity(updated);
    }

    private void verifyFailureAnalysisAccess(
            FailureAnalysis analysis,
            UserPrincipal currentUser,
            boolean writeAccess) {
        TestResult testResult = testResultRepository.findById(analysis.getTestResultId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Test result not found with id: " + analysis.getTestResultId()));
        TestRun testRun = testRunRepository.findById(testResult.getTestRunId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Test run not found with id: " + testResult.getTestRunId()));
        if (writeAccess) {
            projectService.findProjectAndVerifyWriteAccess(testRun.getProjectId(), currentUser);
        } else {
            projectService.findProjectAndVerifyReadAccess(testRun.getProjectId(), currentUser);
        }
    }
}

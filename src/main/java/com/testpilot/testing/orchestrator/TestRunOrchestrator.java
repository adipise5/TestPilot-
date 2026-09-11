package com.testpilot.testing.orchestrator;

import com.testpilot.ai.agent.CodeAnalysisAgent;
import com.testpilot.ai.agent.TestGenerationAgent;
import com.testpilot.ai.dto.CodeAnalysisResponse;
import com.testpilot.ai.dto.TestGenerationResponse;
import com.testpilot.common.exception.ResourceNotFoundException;
import com.testpilot.failure.agent.FailureAnalysisAgent;
import com.testpilot.failure.agent.FixSuggestionAgent;
import com.testpilot.failure.dto.FailureAnalysisResponse;
import com.testpilot.failure.dto.FixSuggestionResponse;
import com.testpilot.failure.entity.FailureAnalysis;
import com.testpilot.failure.entity.FixSuggestion;
import com.testpilot.failure.repository.FailureAnalysisRepository;
import com.testpilot.failure.repository.FixSuggestionRepository;
import com.testpilot.project.entity.CodeFile;
import com.testpilot.project.service.ProjectSourceService;
import com.testpilot.rag.service.RagService;
import com.testpilot.testing.entity.*;
import com.testpilot.testing.execution.TestExecutionService;
import com.testpilot.testing.execution.TestExecutionOutcome;
import com.testpilot.testing.repository.GeneratedTestRepository;
import com.testpilot.testing.repository.TestResultRepository;
import com.testpilot.testing.repository.TestRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TestRunOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TestRunOrchestrator.class);

    private final TestRunRepository testRunRepository;
    private final GeneratedTestRepository generatedTestRepository;
    private final TestResultRepository testResultRepository;
    private final ProjectSourceService projectSourceService;
    private final FailureAnalysisRepository failureAnalysisRepository;
    private final FixSuggestionRepository fixSuggestionRepository;
    private final CodeAnalysisAgent codeAnalysisAgent;
    private final TestGenerationAgent testGenerationAgent;
    private final FailureAnalysisAgent failureAnalysisAgent;
    private final FixSuggestionAgent fixSuggestionAgent;
    private final TestExecutionService testExecutionService;
    private final RagService ragService;

    public TestRunOrchestrator(
            TestRunRepository testRunRepository,
            GeneratedTestRepository generatedTestRepository,
            TestResultRepository testResultRepository,
            ProjectSourceService projectSourceService,
            FailureAnalysisRepository failureAnalysisRepository,
            FixSuggestionRepository fixSuggestionRepository,
            CodeAnalysisAgent codeAnalysisAgent,
            TestGenerationAgent testGenerationAgent,
            FailureAnalysisAgent failureAnalysisAgent,
            FixSuggestionAgent fixSuggestionAgent,
            TestExecutionService testExecutionService,
            RagService ragService) {
        this.testRunRepository = testRunRepository;
        this.generatedTestRepository = generatedTestRepository;
        this.testResultRepository = testResultRepository;
        this.projectSourceService = projectSourceService;
        this.failureAnalysisRepository = failureAnalysisRepository;
        this.fixSuggestionRepository = fixSuggestionRepository;
        this.codeAnalysisAgent = codeAnalysisAgent;
        this.testGenerationAgent = testGenerationAgent;
        this.failureAnalysisAgent = failureAnalysisAgent;
        this.fixSuggestionAgent = fixSuggestionAgent;
        this.testExecutionService = testExecutionService;
        this.ragService = ragService;
    }

    @Async("testRunExecutor")
    public void orchestrateTestRunAsync(Long testRunId) {
        log.info("Starting asynchronous orchestration for TestRun ID: {}", testRunId);
        TestRun testRun = testRunRepository.findById(testRunId)
                .orElseThrow(() -> new ResourceNotFoundException("TestRun not found with id: " + testRunId));

        try {
            List<CodeFile> sourceFiles = projectSourceService.getActiveSourceFiles(testRun.getProjectId());

            // Step 1: Analyze Code
            updateStatus(testRun, TestRunStatus.ANALYZING);
            CodeAnalysisResponse analysis = codeAnalysisAgent.analyzeCode(sourceFiles);

            // Step 2: Query RAG & Generate Tests
            updateStatus(testRun, TestRunStatus.GENERATING_TESTS);
            String primaryContent = sourceFiles.isEmpty() ? "" : sourceFiles.get(0).getContent();
            String ragContext = ragService.getRelevantContextForTesting(primaryContent);

            TestGenerationResponse genResponse = testGenerationAgent.generateTests(sourceFiles, analysis, ragContext);

            String sourceFileName = sourceFiles.isEmpty() ? "Source.java" : sourceFiles.get(0).getFileName();
            GeneratedTest genTest = new GeneratedTest(
                    testRunId,
                    sourceFileName,
                    genResponse.testClass(),
                    genResponse.fullTestCode()
            );
            generatedTestRepository.save(genTest);

            // Step 3: Run Tests & Parse Surefire Reports
            updateStatus(testRun, TestRunStatus.RUNNING_TESTS);
            List<GeneratedTest> genTestList = List.of(genTest);
            TestExecutionOutcome executionOutcome = testExecutionService.executeTests(testRunId, sourceFiles, genTestList);
            List<TestResult> savedResults = testResultRepository.saveAll(executionOutcome.results());
            testRun.recordExecutionOutcome(
                    executionOutcome.type(),
                    executionOutcome.processExitCode(),
                    executionOutcome.output());
            testRunRepository.save(testRun);

            if (!executionOutcome.completedTestProcess()) {
                updateStatus(testRun, TestRunStatus.FAILED);
                log.warn("Test execution did not complete for run {}: {}", testRunId, executionOutcome.type());
                return;
            }

            // Step 4: Failure Analysis & Fix Suggestions if failures exist
            List<TestResult> failedResults = savedResults.stream()
                    .filter(r -> r.getStatus() == TestResultStatus.FAILED || r.getStatus() == TestResultStatus.ERROR)
                    .toList();

            if (!failedResults.isEmpty()) {
                updateStatus(testRun, TestRunStatus.ANALYZING_FAILURES);
                for (TestResult failure : failedResults) {
                    FailureAnalysisResponse agentAnalysis = failureAnalysisAgent.analyzeFailure(
                            primaryContent,
                            genTest.getTestCode(),
                            failure.getTestName(),
                            failure.getErrorMessage(),
                            failure.getStackTrace(),
                            ragContext
                    );

                    FailureAnalysis faEntity = new FailureAnalysis(
                            failure.getId(),
                            agentAnalysis.rootCause(),
                            agentAnalysis.severity(),
                            agentAnalysis.affectedMethod(),
                            agentAnalysis.explanation(),
                            agentAnalysis.confidence()
                    );
                    FailureAnalysis savedFa = failureAnalysisRepository.save(faEntity);

                    FixSuggestionResponse agentFix = fixSuggestionAgent.generateFix(
                            primaryContent,
                            agentAnalysis,
                            failure.getStackTrace(),
                            ragContext
                    );

                    FixSuggestion fixEntity = new FixSuggestion(
                            savedFa.getId(),
                            agentFix.originalCode(),
                            agentFix.suggestedCode(),
                            agentFix.explanation()
                    );
                    fixSuggestionRepository.save(fixEntity);
                }
            }

            // Step 5: Complete Test Run
            updateStatus(testRun, TestRunStatus.COMPLETED);
            log.info("Successfully completed asynchronous TestRun ID: {}", testRunId);

        } catch (Exception e) {
            log.error("Error during asynchronous orchestration of TestRun ID: {}", testRunId, e);
            updateStatus(testRun, TestRunStatus.FAILED);
        }
    }

    private void updateStatus(TestRun testRun, TestRunStatus newStatus) {
        testRun.setStatus(newStatus);
        testRunRepository.save(testRun);
    }
}

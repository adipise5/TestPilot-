package com.testpilot.ai.controller;

import com.testpilot.ai.agent.CodeAnalysisAgent;
import com.testpilot.ai.agent.TestGenerationAgent;
import com.testpilot.ai.dto.CodeAnalysisResponse;
import com.testpilot.ai.dto.TestGenerationResponse;
import com.testpilot.auth.security.UserPrincipal;
import com.testpilot.common.exception.ResourceNotFoundException;
import com.testpilot.project.entity.CodeFile;
import com.testpilot.project.service.ProjectSourceService;
import com.testpilot.project.service.ProjectService;
import com.testpilot.rag.service.RagService;
import com.testpilot.testing.entity.TestRun;
import com.testpilot.testing.repository.TestRunRepository;
import com.testpilot.testing.service.TestRunService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping
public class AiController {

    private final CodeAnalysisAgent codeAnalysisAgent;
    private final TestGenerationAgent testGenerationAgent;
    private final ProjectService projectService;
    private final ProjectSourceService projectSourceService;
    private final TestRunRepository testRunRepository;
    private final TestRunService testRunService;
    private final RagService ragService;

    public AiController(
            CodeAnalysisAgent codeAnalysisAgent,
            TestGenerationAgent testGenerationAgent,
            ProjectService projectService,
            ProjectSourceService projectSourceService,
            TestRunRepository testRunRepository,
            TestRunService testRunService,
            RagService ragService) {
        this.codeAnalysisAgent = codeAnalysisAgent;
        this.testGenerationAgent = testGenerationAgent;
        this.projectService = projectService;
        this.projectSourceService = projectSourceService;
        this.testRunRepository = testRunRepository;
        this.testRunService = testRunService;
        this.ragService = ragService;
    }

    @PostMapping("/api/projects/{id}/analyze")
    public ResponseEntity<CodeAnalysisResponse> analyzeProject(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        projectService.findProjectAndVerifyReadAccess(id, currentUser);
        List<CodeFile> sourceFiles = projectSourceService.getActiveSourceFiles(id);

        CodeAnalysisResponse response = codeAnalysisAgent.analyzeCode(sourceFiles);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/test-runs/{id}/generate-tests")
    public ResponseEntity<TestGenerationResponse> generateTestsForTestRun(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        TestRun testRun = testRunRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("TestRun not found with id: " + id));

        projectService.findProjectAndVerifyWriteAccess(testRun.getProjectId(), currentUser);
        List<CodeFile> sourceFiles = projectSourceService.getActiveSourceFiles(testRun.getProjectId());

        // 1. Analyze code
        CodeAnalysisResponse analysis = codeAnalysisAgent.analyzeCode(sourceFiles);

        // 2. Query RAG engine for relevant testing knowledge
        String primaryCodeContent = sourceFiles.isEmpty() ? "" : sourceFiles.get(0).getContent();
        String ragContext = ragService.getRelevantContextForTesting(primaryCodeContent);

        // 3. Generate test suite augmented with RAG context
        TestGenerationResponse genResponse = testGenerationAgent.generateTests(sourceFiles, analysis, ragContext);

        // 4. Save generated test into testRun
        String sourceFileName = sourceFiles.isEmpty() ? "Source.java" : sourceFiles.get(0).getFileName();
        testRunService.saveGeneratedTest(
                id,
                new com.testpilot.testing.dto.SaveGeneratedTestRequest(
                        sourceFileName,
                        genResponse.testClass(),
                        genResponse.fullTestCode()
                ),
                currentUser
        );

        return ResponseEntity.ok(genResponse);
    }
}

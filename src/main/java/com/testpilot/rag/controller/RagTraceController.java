package com.testpilot.rag.controller;

import com.testpilot.auth.security.UserPrincipal;
import com.testpilot.common.exception.ResourceNotFoundException;
import com.testpilot.project.service.ProjectAuthorizationService;
import com.testpilot.rag.dto.RagRetrievalTraceResponse;
import com.testpilot.rag.service.RagService;
import com.testpilot.testing.repository.TestRunRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/test-runs/{testRunId}/rag-traces")
public class RagTraceController {

    private final RagService ragService;
    private final TestRunRepository testRuns;
    private final ProjectAuthorizationService authorization;

    public RagTraceController(
            RagService ragService,
            TestRunRepository testRuns,
            ProjectAuthorizationService authorization) {
        this.ragService = ragService;
        this.testRuns = testRuns;
        this.authorization = authorization;
    }

    @GetMapping
    public ResponseEntity<List<RagRetrievalTraceResponse>> traces(
            @PathVariable Long testRunId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        var run = testRuns.findById(testRunId)
                .orElseThrow(() -> new ResourceNotFoundException("TestRun not found: " + testRunId));
        authorization.requireReadAccess(run.getProjectId(), currentUser);
        return ResponseEntity.ok(ragService.tracesForTestRun(testRunId));
    }
}

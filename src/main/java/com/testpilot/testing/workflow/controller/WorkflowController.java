package com.testpilot.testing.workflow.controller;

import com.testpilot.auth.security.UserPrincipal;
import com.testpilot.testing.orchestrator.TestRunOrchestrator;
import com.testpilot.testing.workflow.dto.WorkflowDecisionRequest;
import com.testpilot.testing.workflow.dto.WorkflowTraceResponse;
import com.testpilot.testing.workflow.service.WorkflowRunService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/test-runs/{testRunId}/workflow")
public class WorkflowController {

    private final WorkflowRunService workflowRunService;
    private final TestRunOrchestrator orchestrator;

    public WorkflowController(
            WorkflowRunService workflowRunService,
            TestRunOrchestrator orchestrator) {
        this.workflowRunService = workflowRunService;
        this.orchestrator = orchestrator;
    }

    @GetMapping
    public ResponseEntity<WorkflowTraceResponse> getTrace(
            @PathVariable Long testRunId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(workflowRunService.getTrace(testRunId, currentUser));
    }

    @PostMapping("/decision")
    public ResponseEntity<WorkflowTraceResponse> decide(
            @PathVariable Long testRunId,
            @Valid @RequestBody WorkflowDecisionRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        workflowRunService.recordDecision(testRunId, request, currentUser);
        orchestrator.resumeTestRunAsync(testRunId, request.approved(), request.comment());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(workflowRunService.getTrace(testRunId, currentUser));
    }
}

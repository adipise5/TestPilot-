package com.testpilot.observability.controller;

import com.testpilot.auth.security.UserPrincipal;
import com.testpilot.common.exception.ResourceNotFoundException;
import com.testpilot.observability.dto.TestRunObservabilityResponse;
import com.testpilot.observability.service.TestRunObservabilityService;
import com.testpilot.project.service.ProjectAuthorizationService;
import com.testpilot.testing.repository.TestRunRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/test-runs/{testRunId}/observability")
public class TestRunObservabilityController {

    private final TestRunRepository testRuns;
    private final ProjectAuthorizationService authorization;
    private final TestRunObservabilityService observability;

    public TestRunObservabilityController(
            TestRunRepository testRuns,
            ProjectAuthorizationService authorization,
            TestRunObservabilityService observability) {
        this.testRuns = testRuns;
        this.authorization = authorization;
        this.observability = observability;
    }

    @GetMapping
    public ResponseEntity<TestRunObservabilityResponse> summarize(
            @PathVariable Long testRunId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        var testRun = testRuns.findById(testRunId)
                .orElseThrow(() -> new ResourceNotFoundException("TestRun not found: " + testRunId));
        authorization.requireReadAccess(testRun.getProjectId(), currentUser);
        return ResponseEntity.ok(observability.summarize(testRunId));
    }
}

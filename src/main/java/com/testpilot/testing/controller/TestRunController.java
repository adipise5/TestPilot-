package com.testpilot.testing.controller;

import com.testpilot.auth.security.UserPrincipal;
import com.testpilot.testing.dto.*;
import com.testpilot.testing.orchestrator.TestRunOrchestrator;
import com.testpilot.testing.service.TestRunService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping
public class TestRunController {

    private final TestRunService testRunService;
    private final TestRunOrchestrator testRunOrchestrator;

    public TestRunController(TestRunService testRunService, TestRunOrchestrator testRunOrchestrator) {
        this.testRunService = testRunService;
        this.testRunOrchestrator = testRunOrchestrator;
    }

    @PostMapping("/api/projects/{projectId}/test-runs")
    public ResponseEntity<TestRunResponse> createTestRun(
            @PathVariable Long projectId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        TestRunResponse response = testRunService.createTestRun(projectId, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/api/projects/{projectId}/test-runs/auto")
    public ResponseEntity<TestRunResponse> startAutomatedTestRun(
            @PathVariable Long projectId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        TestRunResponse initialRun = testRunService.createTestRun(projectId, currentUser);
        testRunOrchestrator.orchestrateTestRunAsync(initialRun.id());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(initialRun);
    }

    @GetMapping("/api/projects/{projectId}/test-runs")
    public ResponseEntity<List<TestRunResponse>> getTestRunsByProject(
            @PathVariable Long projectId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        List<TestRunResponse> response = testRunService.getTestRunsByProject(projectId, currentUser);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/test-runs/{id}/tests")
    public ResponseEntity<GeneratedTestResponse> saveGeneratedTest(
            @PathVariable Long id,
            @Valid @RequestBody SaveGeneratedTestRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        GeneratedTestResponse response = testRunService.saveGeneratedTest(id, request, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/api/test-runs/{id}/execute")
    public ResponseEntity<TestRunResponse> executeTestRun(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        TestRunResponse response = testRunService.executeTestRun(id, currentUser);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/test-runs/{id}")
    public ResponseEntity<TestRunResponse> getTestRun(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        TestRunResponse response = testRunService.getTestRun(id, currentUser);
        return ResponseEntity.ok(response);
    }
}

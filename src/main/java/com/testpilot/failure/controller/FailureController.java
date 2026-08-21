package com.testpilot.failure.controller;

import com.testpilot.auth.security.UserPrincipal;
import com.testpilot.failure.dto.FailureAnalysisResponse;
import com.testpilot.failure.dto.FixSuggestionResponse;
import com.testpilot.failure.entity.FixStatus;
import com.testpilot.failure.service.FailureService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping
public class FailureController {

    private final FailureService failureService;

    public FailureController(FailureService failureService) {
        this.failureService = failureService;
    }

    @PostMapping("/api/failures/{testResultId}/analyze")
    public ResponseEntity<FailureAnalysisResponse> analyzeFailure(
            @PathVariable Long testResultId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        FailureAnalysisResponse response = failureService.analyzeFailure(testResultId, currentUser);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/failures/{testResultId}")
    public ResponseEntity<FailureAnalysisResponse> getFailureAnalysis(
            @PathVariable Long testResultId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        FailureAnalysisResponse response = failureService.getFailureAnalysis(testResultId, currentUser);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/failures/analysis/{failureAnalysisId}/fix")
    public ResponseEntity<FixSuggestionResponse> getFixSuggestion(
            @PathVariable Long failureAnalysisId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        FixSuggestionResponse response = failureService.getFixSuggestion(failureAnalysisId, currentUser);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/fix-suggestions/{id}/accept")
    public ResponseEntity<FixSuggestionResponse> acceptFixSuggestion(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        FixSuggestionResponse response = failureService.updateFixStatus(id, FixStatus.ACCEPTED, currentUser);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/fix-suggestions/{id}/reject")
    public ResponseEntity<FixSuggestionResponse> rejectFixSuggestion(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        FixSuggestionResponse response = failureService.updateFixStatus(id, FixStatus.REJECTED, currentUser);
        return ResponseEntity.ok(response);
    }
}

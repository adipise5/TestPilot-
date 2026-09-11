package com.testpilot.repository.controller;

import com.testpilot.auth.security.UserPrincipal;
import com.testpilot.repository.dto.GitHubInstallationCallbackResponse;
import com.testpilot.repository.dto.GitHubInstallationStartResponse;
import com.testpilot.repository.service.GitHubInstallationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/integrations/github/install")
public class GitHubInstallationController {

    private final GitHubInstallationService installationService;

    public GitHubInstallationController(GitHubInstallationService installationService) {
        this.installationService = installationService;
    }

    @PostMapping("/start")
    public ResponseEntity<GitHubInstallationStartResponse> start(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(installationService.start(currentUser));
    }

    @GetMapping("/callback")
    public ResponseEntity<GitHubInstallationCallbackResponse> callback(
            @RequestParam String state,
            @RequestParam(name = "installation_id") Long installationId,
            @RequestParam(name = "setup_action", required = false) String setupAction) {
        return ResponseEntity.ok(installationService.complete(state, installationId, setupAction));
    }
}

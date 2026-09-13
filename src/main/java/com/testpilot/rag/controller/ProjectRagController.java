package com.testpilot.rag.controller;

import com.testpilot.auth.security.UserPrincipal;
import com.testpilot.project.service.ProjectAuthorizationService;
import com.testpilot.rag.dto.ProjectRagQueryRequest;
import com.testpilot.rag.dto.RagRetrievalResult;
import com.testpilot.rag.service.RagService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/rag")
public class ProjectRagController {

    private final RagService ragService;
    private final ProjectAuthorizationService authorization;

    public ProjectRagController(RagService ragService, ProjectAuthorizationService authorization) {
        this.ragService = ragService;
        this.authorization = authorization;
    }

    @PostMapping("/query")
    public ResponseEntity<RagRetrievalResult> query(
            @PathVariable Long projectId,
            @Valid @RequestBody ProjectRagQueryRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        authorization.requireReadAccess(projectId, currentUser);
        return ResponseEntity.ok(ragService.retrieveForProject(
                projectId,
                request.commitSha(),
                request.query(),
                request.topK() == null ? 8 : request.topK(),
                request.tokenBudget()));
    }
}

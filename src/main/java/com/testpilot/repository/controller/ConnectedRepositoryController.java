package com.testpilot.repository.controller;

import com.testpilot.auth.security.UserPrincipal;
import com.testpilot.repository.connector.RepositoryTransport;
import com.testpilot.repository.dto.*;
import com.testpilot.repository.service.ConnectedRepositoryService;
import com.testpilot.repository.service.RepositoryIngestionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ConnectedRepositoryController {

    private final ConnectedRepositoryService connectedRepositoryService;
    private final RepositoryIngestionService ingestionService;

    public ConnectedRepositoryController(
            ConnectedRepositoryService connectedRepositoryService,
            RepositoryIngestionService ingestionService) {
        this.connectedRepositoryService = connectedRepositoryService;
        this.ingestionService = ingestionService;
    }

    @GetMapping("/repository-providers/github/repositories")
    public ResponseEntity<List<RemoteRepositoryResponse>> discoverRepositories(
            @RequestParam RepositoryTransport transport,
            @RequestParam(required = false) Long installationId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(connectedRepositoryService.discover(transport, installationId, currentUser));
    }

    @PostMapping("/projects/{projectId}/repository")
    public ResponseEntity<RepositoryConnectionResult> connect(
            @PathVariable Long projectId,
            @Valid @RequestBody ConnectRepositoryRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(connectedRepositoryService.connect(projectId, request, currentUser));
    }

    @GetMapping("/projects/{projectId}/repository")
    public ResponseEntity<ConnectedRepositoryResponse> getConnection(
            @PathVariable Long projectId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(connectedRepositoryService.getByProject(projectId, currentUser));
    }

    @PostMapping("/repositories/{repositoryId}/ingestions")
    public ResponseEntity<RepositoryIngestionResponse> refresh(
            @PathVariable Long repositoryId,
            @Valid @RequestBody RefreshRepositoryRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ingestionService.ingest(repositoryId, request.revision(), currentUser));
    }

    @GetMapping("/repositories/{repositoryId}/ingestions")
    public ResponseEntity<List<RepositoryIngestionResponse>> listIngestions(
            @PathVariable Long repositoryId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ingestionService.listIngestions(repositoryId, currentUser));
    }

    @GetMapping("/repositories/{repositoryId}/catalog")
    public ResponseEntity<List<RepositoryArtifactResponse>> getCatalog(
            @PathVariable Long repositoryId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ingestionService.getSelectedCatalog(repositoryId, currentUser));
    }

    @DeleteMapping("/repositories/{repositoryId}")
    public ResponseEntity<Void> disconnect(
            @PathVariable Long repositoryId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        connectedRepositoryService.disconnect(repositoryId, currentUser);
        return ResponseEntity.noContent().build();
    }
}

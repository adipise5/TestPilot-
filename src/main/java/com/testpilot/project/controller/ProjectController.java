package com.testpilot.project.controller;

import com.testpilot.auth.security.UserPrincipal;
import com.testpilot.project.dto.*;
import com.testpilot.project.service.CodeFileService;
import com.testpilot.project.service.ProjectService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final CodeFileService codeFileService;

    public ProjectController(ProjectService projectService, CodeFileService codeFileService) {
        this.projectService = projectService;
        this.codeFileService = codeFileService;
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> createProject(
            @Valid @RequestBody CreateProjectRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        ProjectResponse response = projectService.createProject(request, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<ProjectResponse>> getAllProjects(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        List<ProjectResponse> response = projectService.getAllProjects(currentUser);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProjectResponse> getProjectById(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        ProjectResponse response = projectService.getProjectById(id, currentUser);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProjectResponse> updateProject(
            @PathVariable Long id,
            @Valid @RequestBody UpdateProjectRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        ProjectResponse response = projectService.updateProject(id, request, currentUser);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProject(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        projectService.deleteProject(id, currentUser);
        return ResponseEntity.noContent().build();
    }

    // Code File Endpoints
    @PostMapping("/{id}/files")
    public ResponseEntity<CodeFileResponse> addCodeFile(
            @PathVariable Long id,
            @Valid @RequestBody CreateCodeFileRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        CodeFileResponse response = codeFileService.addCodeFile(id, request, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}/files")
    public ResponseEntity<List<CodeFileResponse>> getCodeFiles(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        List<CodeFileResponse> response = codeFileService.getCodeFiles(id, currentUser);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/files/{fileId}")
    public ResponseEntity<CodeFileResponse> getCodeFile(
            @PathVariable Long id,
            @PathVariable Long fileId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        CodeFileResponse response = codeFileService.getCodeFile(id, fileId, currentUser);
        return ResponseEntity.ok(response);
    }
}

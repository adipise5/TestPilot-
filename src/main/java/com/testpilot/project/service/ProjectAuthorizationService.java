package com.testpilot.project.service;

import com.testpilot.auth.entity.Role;
import com.testpilot.auth.security.UserPrincipal;
import com.testpilot.common.exception.ResourceNotFoundException;
import com.testpilot.project.entity.Project;
import com.testpilot.project.repository.ProjectRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class ProjectAuthorizationService {

    private final ProjectRepository projectRepository;

    public ProjectAuthorizationService(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    public Project requireReadAccess(Long projectId, UserPrincipal currentUser) {
        Project project = findProject(projectId);
        if (currentUser.getRole() == Role.DEVELOPER && !isOwner(project, currentUser)) {
            throw new AccessDeniedException("Project access denied");
        }
        return project;
    }

    public Project requireWriteAccess(Long projectId, UserPrincipal currentUser) {
        Project project = findProject(projectId);
        if (currentUser.getRole() != Role.ADMIN && !isOwner(project, currentUser)) {
            throw new AccessDeniedException("Project modification denied");
        }
        return project;
    }

    private Project findProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + projectId));
    }

    private boolean isOwner(Project project, UserPrincipal currentUser) {
        return project.getOwnerId().equals(currentUser.getId());
    }
}

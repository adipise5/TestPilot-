package com.testpilot.project.service;

import com.testpilot.auth.entity.Role;
import com.testpilot.auth.security.UserPrincipal;
import com.testpilot.common.exception.ResourceNotFoundException;
import com.testpilot.project.dto.CreateProjectRequest;
import com.testpilot.project.dto.ProjectResponse;
import com.testpilot.project.dto.UpdateProjectRequest;
import com.testpilot.project.entity.Project;
import com.testpilot.project.repository.CodeFileRepository;
import com.testpilot.project.repository.ProjectRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final CodeFileRepository codeFileRepository;

    public ProjectService(ProjectRepository projectRepository, CodeFileRepository codeFileRepository) {
        this.projectRepository = projectRepository;
        this.codeFileRepository = codeFileRepository;
    }

    @Transactional
    public ProjectResponse createProject(CreateProjectRequest request, UserPrincipal currentUser) {
        Project project = new Project(request.name(), request.description(), currentUser.getId());
        Project saved = projectRepository.save(project);
        return ProjectResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public ProjectResponse getProjectById(Long id, UserPrincipal currentUser) {
        Project project = findProjectAndVerifyReadAccess(id, currentUser);
        return ProjectResponse.fromEntity(project);
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> getAllProjects(UserPrincipal currentUser) {
        List<Project> projects;
        if (currentUser.getRole() == Role.DEVELOPER) {
            projects = projectRepository.findByOwnerId(currentUser.getId());
        } else {
            projects = projectRepository.findAll();
        }
        return projects.stream().map(ProjectResponse::fromEntity).toList();
    }

    @Transactional
    public ProjectResponse updateProject(Long id, UpdateProjectRequest request, UserPrincipal currentUser) {
        Project project = findProjectAndVerifyWriteAccess(id, currentUser);
        project.setName(request.name());
        project.setDescription(request.description());
        Project updated = projectRepository.save(project);
        return ProjectResponse.fromEntity(updated);
    }

    @Transactional
    public void deleteProject(Long id, UserPrincipal currentUser) {
        Project project = findProjectAndVerifyWriteAccess(id, currentUser);
        codeFileRepository.deleteAll(codeFileRepository.findByProjectId(id));
        projectRepository.delete(project);
    }

    public Project findProjectAndVerifyReadAccess(Long id, UserPrincipal currentUser) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + id));

        if (currentUser.getRole() == Role.DEVELOPER && !project.getOwnerId().equals(currentUser.getId())) {
            throw new AccessDeniedException("You do not have access to this project");
        }
        return project;
    }

    public Project findProjectAndVerifyWriteAccess(Long id, UserPrincipal currentUser) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + id));

        if (currentUser.getRole() != Role.ADMIN && !project.getOwnerId().equals(currentUser.getId())) {
            throw new AccessDeniedException("You do not have permission to modify this project");
        }
        return project;
    }
}

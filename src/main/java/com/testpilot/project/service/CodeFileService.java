package com.testpilot.project.service;

import com.testpilot.auth.security.UserPrincipal;
import com.testpilot.common.exception.ResourceNotFoundException;
import com.testpilot.project.dto.CodeFileResponse;
import com.testpilot.project.dto.CreateCodeFileRequest;
import com.testpilot.project.entity.CodeFile;
import com.testpilot.project.repository.CodeFileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CodeFileService {

    private final CodeFileRepository codeFileRepository;
    private final ProjectService projectService;

    public CodeFileService(CodeFileRepository codeFileRepository, ProjectService projectService) {
        this.codeFileRepository = codeFileRepository;
        this.projectService = projectService;
    }

    @Transactional
    public CodeFileResponse addCodeFile(Long projectId, CreateCodeFileRequest request, UserPrincipal currentUser) {
        projectService.findProjectAndVerifyWriteAccess(projectId, currentUser);

        CodeFile codeFile = new CodeFile(
                projectId,
                request.fileName(),
                request.filePath(),
                request.content()
        );

        CodeFile saved = codeFileRepository.save(codeFile);
        return CodeFileResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public List<CodeFileResponse> getCodeFiles(Long projectId, UserPrincipal currentUser) {
        projectService.findProjectAndVerifyReadAccess(projectId, currentUser);
        return codeFileRepository.findByProjectId(projectId).stream()
                .map(CodeFileResponse::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public CodeFileResponse getCodeFile(Long projectId, Long fileId, UserPrincipal currentUser) {
        projectService.findProjectAndVerifyReadAccess(projectId, currentUser);

        CodeFile codeFile = codeFileRepository.findByIdAndProjectId(fileId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Code file not found with id: " + fileId + " in project: " + projectId));

        return CodeFileResponse.fromEntity(codeFile);
    }
}

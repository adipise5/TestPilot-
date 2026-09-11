package com.testpilot.project.service;

import com.testpilot.auth.security.UserPrincipal;
import com.testpilot.common.exception.InvalidRequestException;
import com.testpilot.common.exception.ResourceNotFoundException;
import com.testpilot.common.validation.RepositoryPathPolicy;
import com.testpilot.project.dto.CodeFileResponse;
import com.testpilot.project.dto.CreateCodeFileRequest;
import com.testpilot.project.entity.CodeFile;
import com.testpilot.project.repository.CodeFileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CodeFileService {

    private static final int MAX_FILES_PER_PROJECT = 200;
    private static final long MAX_PROJECT_SOURCE_BYTES = 5L * 1024 * 1024;

    private final CodeFileRepository codeFileRepository;
    private final ProjectService projectService;
    private final RepositoryPathPolicy pathPolicy;
    private final ProjectSourceService projectSourceService;

    public CodeFileService(
            CodeFileRepository codeFileRepository,
            ProjectService projectService,
            RepositoryPathPolicy pathPolicy,
            ProjectSourceService projectSourceService) {
        this.codeFileRepository = codeFileRepository;
        this.projectService = projectService;
        this.pathPolicy = pathPolicy;
        this.projectSourceService = projectSourceService;
    }

    @Transactional
    public CodeFileResponse addCodeFile(Long projectId, CreateCodeFileRequest request, UserPrincipal currentUser) {
        projectService.findProjectAndVerifyWriteAccess(projectId, currentUser);
        if (projectSourceService.hasConnectedRepository(projectId)) {
            throw new InvalidRequestException(
                    "Manual source upload is disabled while a repository is connected");
        }

        String normalizedPath = pathPolicy.validateSourcePath(request.fileName(), request.filePath());
        List<CodeFile> existingFiles = codeFileRepository.findByProjectId(projectId);
        if (existingFiles.size() >= MAX_FILES_PER_PROJECT) {
            throw new InvalidRequestException("A project may contain at most " + MAX_FILES_PER_PROJECT + " source files");
        }
        if (codeFileRepository.existsByProjectIdAndFilePath(projectId, normalizedPath)) {
            throw new InvalidRequestException("A source file already exists at this path");
        }

        long existingBytes = existingFiles.stream().mapToLong(file -> utf8Length(file.getContent())).sum();
        if (existingBytes + utf8Length(request.content()) > MAX_PROJECT_SOURCE_BYTES) {
            throw new InvalidRequestException("Project source content exceeds the 5 MiB limit");
        }

        CodeFile codeFile = new CodeFile(
                projectId,
                request.fileName(),
                normalizedPath,
                request.content()
        );

        CodeFile saved = codeFileRepository.save(codeFile);
        return CodeFileResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public List<CodeFileResponse> getCodeFiles(Long projectId, UserPrincipal currentUser) {
        projectService.findProjectAndVerifyReadAccess(projectId, currentUser);
        return projectSourceService.getActiveSourceFiles(projectId).stream()
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

    private long utf8Length(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }
}

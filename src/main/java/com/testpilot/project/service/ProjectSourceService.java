package com.testpilot.project.service;

import com.testpilot.project.entity.CodeFile;
import com.testpilot.project.entity.CodeFileOrigin;
import com.testpilot.project.repository.CodeFileRepository;
import com.testpilot.repository.entity.RepositoryConnectionStatus;
import com.testpilot.repository.repository.ConnectedRepositoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProjectSourceService {

    private final CodeFileRepository codeFileRepository;
    private final ConnectedRepositoryRepository connectedRepositoryRepository;

    public ProjectSourceService(
            CodeFileRepository codeFileRepository,
            ConnectedRepositoryRepository connectedRepositoryRepository) {
        this.codeFileRepository = codeFileRepository;
        this.connectedRepositoryRepository = connectedRepositoryRepository;
    }

    @Transactional(readOnly = true)
    public List<CodeFile> getActiveSourceFiles(Long projectId) {
        return connectedRepositoryRepository.findByProjectId(projectId)
                .filter(repository -> repository.getStatus() == RepositoryConnectionStatus.CONNECTED)
                .map(repository -> codeFileRepository.findByProjectIdAndOriginAndCommitSha(
                        projectId, CodeFileOrigin.REPOSITORY, repository.getSelectedCommitSha()))
                .filter(files -> !files.isEmpty())
                .orElseGet(() -> codeFileRepository.findByProjectIdAndOrigin(projectId, CodeFileOrigin.MANUAL));
    }

    @Transactional(readOnly = true)
    public boolean hasConnectedRepository(Long projectId) {
        return connectedRepositoryRepository.findByProjectId(projectId)
                .map(repository -> repository.getStatus() == RepositoryConnectionStatus.CONNECTED)
                .orElse(false);
    }
}

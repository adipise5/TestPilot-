package com.testpilot.repository.service;

import com.testpilot.common.exception.ResourceNotFoundException;
import com.testpilot.project.entity.CodeFile;
import com.testpilot.project.entity.CodeFileOrigin;
import com.testpilot.project.repository.CodeFileRepository;
import com.testpilot.repository.entity.*;
import com.testpilot.repository.repository.ConnectedRepositoryRepository;
import com.testpilot.repository.repository.RepositoryArtifactRepository;
import com.testpilot.repository.repository.RepositoryIngestionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class RepositoryIngestionPersistenceService {

    private final RepositoryIngestionRepository ingestionRepository;
    private final RepositoryArtifactRepository artifactRepository;
    private final ConnectedRepositoryRepository connectedRepositoryRepository;
    private final CodeFileRepository codeFileRepository;

    public RepositoryIngestionPersistenceService(
            RepositoryIngestionRepository ingestionRepository,
            RepositoryArtifactRepository artifactRepository,
            ConnectedRepositoryRepository connectedRepositoryRepository,
            CodeFileRepository codeFileRepository) {
        this.ingestionRepository = ingestionRepository;
        this.artifactRepository = artifactRepository;
        this.connectedRepositoryRepository = connectedRepositoryRepository;
        this.codeFileRepository = codeFileRepository;
    }

    @Transactional
    public BeginIngestionResult begin(Long repositoryId, String commitSha) {
        var existing = ingestionRepository.findByConnectedRepositoryIdAndCommitSha(repositoryId, commitSha);
        if (existing.isEmpty()) {
            RepositoryIngestion created = ingestionRepository.save(new RepositoryIngestion(repositoryId, commitSha));
            return new BeginIngestionResult(created, true);
        }

        RepositoryIngestion ingestion = existing.get();
        if (ingestion.getStatus() == RepositoryIngestionStatus.COMPLETED
                || ingestion.getStatus() == RepositoryIngestionStatus.RUNNING) {
            return new BeginIngestionResult(ingestion, false);
        }
        artifactRepository.deleteByIngestionId(ingestion.getId());
        ingestion.retry();
        return new BeginIngestionResult(ingestionRepository.save(ingestion), true);
    }

    @Transactional
    public RepositoryIngestion complete(
            Long ingestionId,
            Long repositoryId,
            String commitSha,
            BuildSystem buildSystem,
            String catalogHash,
            List<RepositoryCatalogPolicy.CatalogArtifact> artifacts) {
        RepositoryIngestion ingestion = ingestionRepository.findById(ingestionId)
                .orElseThrow(() -> new ResourceNotFoundException("Repository ingestion not found: " + ingestionId));
        artifactRepository.deleteByIngestionId(ingestionId);
        List<RepositoryArtifact> entities = artifacts.stream()
                .map(artifact -> new RepositoryArtifact(
                        ingestionId,
                        artifact.path(),
                        artifact.objectSha(),
                        artifact.contentHash(),
                        artifact.kind(),
                        artifact.sizeBytes(),
                        artifact.content()))
                .toList();
        artifactRepository.saveAll(entities);
        ingestion.complete(buildSystem, entities.size(), catalogHash);

        ConnectedRepository repository = connectedRepositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Connected repository not found: " + repositoryId));
        repository.selectCommit(commitSha);
        connectedRepositoryRepository.save(repository);

        codeFileRepository.deleteByProjectIdAndOrigin(repository.getProjectId(), CodeFileOrigin.REPOSITORY);
        List<CodeFile> repositorySources = artifacts.stream()
                .filter(artifact -> artifact.kind() == RepositoryArtifactKind.JAVA_SOURCE)
                .map(artifact -> CodeFile.fromRepository(
                        repository.getProjectId(),
                        repositoryId,
                        commitSha,
                        artifact.path().substring(artifact.path().lastIndexOf('/') + 1),
                        artifact.path(),
                        artifact.content()))
                .toList();
        codeFileRepository.saveAll(repositorySources);
        return ingestionRepository.save(ingestion);
    }

    @Transactional
    public void fail(Long ingestionId, String reason) {
        ingestionRepository.findById(ingestionId).ifPresent(ingestion -> {
            ingestion.fail(reason);
            ingestionRepository.save(ingestion);
        });
    }

    public record BeginIngestionResult(RepositoryIngestion ingestion, boolean shouldProcess) {}
}

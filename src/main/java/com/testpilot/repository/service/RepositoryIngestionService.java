package com.testpilot.repository.service;

import com.testpilot.auth.security.UserPrincipal;
import com.testpilot.common.exception.InvalidRequestException;
import com.testpilot.common.exception.ResourceNotFoundException;
import com.testpilot.project.service.ProjectService;
import com.testpilot.repository.connector.*;
import com.testpilot.repository.dto.RepositoryArtifactResponse;
import com.testpilot.repository.dto.RepositoryIngestionResponse;
import com.testpilot.repository.dto.RepositorySelectionResponse;
import com.testpilot.repository.dto.RepositorySelectionResponse.FileDecision;
import com.testpilot.repository.entity.*;
import com.testpilot.repository.repository.ConnectedRepositoryRepository;
import com.testpilot.repository.repository.RepositoryArtifactRepository;
import com.testpilot.repository.repository.RepositoryIngestionRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class RepositoryIngestionService {

    private final ConnectedRepositoryRepository connectedRepositoryRepository;
    private final RepositoryIngestionRepository ingestionRepository;
    private final RepositoryArtifactRepository artifactRepository;
    private final RepositoryConnectorRegistry connectorRegistry;
    private final RepositoryCatalogPolicy catalogPolicy;
    private final RepositoryIngestionPersistenceService persistenceService;
    private final ProjectService projectService;
    private final GitHubInstallationService installationService;
    private final RepositoryAuditService auditService;

    public RepositoryIngestionService(
            ConnectedRepositoryRepository connectedRepositoryRepository,
            RepositoryIngestionRepository ingestionRepository,
            RepositoryArtifactRepository artifactRepository,
            RepositoryConnectorRegistry connectorRegistry,
            RepositoryCatalogPolicy catalogPolicy,
            RepositoryIngestionPersistenceService persistenceService,
            ProjectService projectService,
            GitHubInstallationService installationService,
            RepositoryAuditService auditService) {
        this.connectedRepositoryRepository = connectedRepositoryRepository;
        this.ingestionRepository = ingestionRepository;
        this.artifactRepository = artifactRepository;
        this.connectorRegistry = connectorRegistry;
        this.catalogPolicy = catalogPolicy;
        this.persistenceService = persistenceService;
        this.projectService = projectService;
        this.installationService = installationService;
        this.auditService = auditService;
    }

    public RepositoryIngestionResponse ingest(Long repositoryId, String requestedRevision, UserPrincipal currentUser) {
        ConnectedRepository repository = requireWritable(repositoryId, currentUser);
        RepositoryConnector connector = connectorRegistry.require(repository.getTransport());
        RepositoryCoordinates coordinates = coordinates(repository);
        RepositoryAccessContext accessContext = accessContext(repository, currentUser);

        String revision = requestedRevision == null || requestedRevision.isBlank()
                ? repository.getSelectedCommitSha()
                : requestedRevision;
        String commitSha = connector.resolveRevision(coordinates, revision, accessContext);
        RepositoryIngestionPersistenceService.BeginIngestionResult begin =
                persistenceService.begin(repositoryId, commitSha);
        if (!begin.shouldProcess()) {
            return RepositoryIngestionResponse.fromEntity(begin.ingestion());
        }

        try {
            var tree = connector.listTree(coordinates, commitSha, accessContext);
            if (tree.size() > 50_000) throw new InvalidRequestException("Repository exceeds the 50,000-entry inventory limit");
            List<FileDecision> decisions = new ArrayList<>();
            for (var entry : tree) {
                if ("tree".equals(entry.type())) continue;
                if (catalogPolicy.classify(entry).isEmpty()) {
                    String reason = catalogPolicy.exclusionReason(entry);
                    decisions.add(new FileDecision(entry.path(), RepositoryCatalogPolicy.language(entry.path()),
                            "EXCLUDED", reason == null ? "Unrecognized source extension or non-source asset" : reason));
                }
            }
            List<ClassifiedEntry> candidates = tree.stream()
                    .map(entry -> catalogPolicy.classify(entry)
                            .map(kind -> new ClassifiedEntry(entry, kind))
                            .orElse(null))
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparing(candidate -> candidate.entry().path()))
                    .toList();
            if (candidates.size() > RepositoryCatalogPolicy.MAX_CATALOG_FILES) {
                throw new InvalidRequestException("Repository exceeds the 1,000-file ingestion catalog limit");
            }

            List<RepositoryCatalogPolicy.CatalogArtifact> artifacts = new ArrayList<>();
            long totalBytes = 0;
            int excludedFiles = 0;
            for (ClassifiedEntry candidate : candidates) {
                RepositoryFileContent file = connector.readFile(
                        coordinates, commitSha, candidate.entry(), accessContext);
                RepositoryCatalogPolicy.CatalogArtifact artifact;
                try {
                    artifact = catalogPolicy.decode(file, candidate.kind());
                } catch (InvalidRequestException excluded) {
                    excludedFiles++;
                    decisions.add(new FileDecision(candidate.entry().path(), RepositoryCatalogPolicy.language(candidate.entry().path()),
                            "EXCLUDED", excluded.getMessage()));
                    continue;
                }
                if (totalBytes + artifact.sizeBytes() > RepositoryCatalogPolicy.MAX_CATALOG_BYTES) {
                    throw new InvalidRequestException("Repository exceeds the 10 MiB ingestion content limit");
                }
                totalBytes += artifact.sizeBytes();
                artifacts.add(artifact);
                decisions.add(new FileDecision(artifact.path(), RepositoryCatalogPolicy.language(artifact.path()),
                        artifact.kind().name(), switch (artifact.kind()) {
                            case BUILD_MANIFEST, BUILD_CONFIGURATION -> "Read-only dependency/test configuration; not executed";
                            case DOCUMENTATION -> "Repository documentation context";
                            case EXISTING_TEST -> "Existing test context";
                            default -> "Source code selected for analysis";
                        }));
            }

            BuildSystem buildSystem = catalogPolicy.detectBuildSystem(artifacts);
            RepositoryIngestion completed = persistenceService.complete(
                    begin.ingestion().getId(),
                    repositoryId,
                    commitSha,
                    buildSystem,
                    catalogPolicy.catalogHash(artifacts),
                    artifacts, encodeSelection(new RepositorySelectionResponse(commitSha,
                            decisions.stream().sorted(Comparator.comparing(FileDecision::path)).toList(),
                            catalogPolicy.buildContexts(artifacts))));
            auditService.record(
                    repositoryId,
                    currentUser.getId(),
                    repository.getInstallationId(),
                    repository.getOwner(),
                    repository.getName(),
                    "INGEST_REVISION",
                    "SUCCESS",
                    "commit=" + commitSha + ", files=" + artifacts.size() + ", excluded=" + excludedFiles);
            return RepositoryIngestionResponse.fromEntity(completed);
        } catch (RuntimeException e) {
            persistenceService.fail(begin.ingestion().getId(), safeFailureReason(e));
            auditService.record(
                    repositoryId,
                    currentUser.getId(),
                    repository.getInstallationId(),
                    repository.getOwner(),
                    repository.getName(),
                    "INGEST_REVISION",
                    "FAILED",
                    "commit=" + commitSha);
            throw e;
        }
    }

    public List<RepositoryIngestionResponse> listIngestions(Long repositoryId, UserPrincipal currentUser) {
        requireReadable(repositoryId, currentUser);
        return ingestionRepository.findByConnectedRepositoryIdOrderByStartedAtDesc(repositoryId).stream()
                .map(RepositoryIngestionResponse::fromEntity)
                .toList();
    }

    public List<RepositoryArtifactResponse> getSelectedCatalog(Long repositoryId, UserPrincipal currentUser) {
        ConnectedRepository repository = requireReadable(repositoryId, currentUser);
        RepositoryIngestion ingestion = ingestionRepository
                .findByConnectedRepositoryIdAndCommitSha(repositoryId, repository.getSelectedCommitSha())
                .filter(item -> item.getStatus() == RepositoryIngestionStatus.COMPLETED)
                .orElseThrow(() -> new ResourceNotFoundException("No completed catalog exists for the selected commit"));
        return artifactRepository.findByIngestionIdOrderByPath(ingestion.getId()).stream()
                .map(RepositoryArtifactResponse::fromEntity)
                .toList();
    }

    public RepositorySelectionResponse getSelection(Long repositoryId, UserPrincipal currentUser) {
        ConnectedRepository repository = requireReadable(repositoryId, currentUser);
        var ingestion = ingestionRepository.findByConnectedRepositoryIdAndCommitSha(repositoryId, repository.getSelectedCommitSha())
                .filter(item -> item.getStatus() == RepositoryIngestionStatus.COMPLETED)
                .orElseThrow(() -> new ResourceNotFoundException("No completed selection exists for this commit"));
        if (ingestion.getSelectionReport() == null) {
            throw new ResourceNotFoundException("Legacy catalog: refresh the branch to generate file-selection evidence");
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(ingestion.getSelectionReport(), RepositorySelectionResponse.class);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Stored selection evidence could not be read", ex);
        }
    }

    private String encodeSelection(RepositorySelectionResponse selection) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(selection);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Selection evidence could not be saved", ex);
        }
    }

    private ConnectedRepository requireWritable(Long repositoryId, UserPrincipal currentUser) {
        ConnectedRepository repository = find(repositoryId);
        projectService.findProjectAndVerifyWriteAccess(repository.getProjectId(), currentUser);
        verifyActiveAndScoped(repository, currentUser);
        return repository;
    }

    private ConnectedRepository requireReadable(Long repositoryId, UserPrincipal currentUser) {
        ConnectedRepository repository = find(repositoryId);
        projectService.findProjectAndVerifyReadAccess(repository.getProjectId(), currentUser);
        return repository;
    }

    private ConnectedRepository find(Long repositoryId) {
        return connectedRepositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Connected repository not found: " + repositoryId));
    }

    private void verifyActiveAndScoped(ConnectedRepository repository, UserPrincipal currentUser) {
        if (repository.getStatus() != RepositoryConnectionStatus.CONNECTED) {
            throw new InvalidRequestException("Repository connection is disconnected");
        }
        if (repository.getTransport() == RepositoryTransport.GITHUB_APP_REST) {
            installationService.requireGrant(currentUser.getId(), repository.getInstallationId());
        }
    }

    private RepositoryCoordinates coordinates(ConnectedRepository repository) {
        return new RepositoryCoordinates(repository.getOwner(), repository.getName());
    }

    private RepositoryAccessContext accessContext(ConnectedRepository repository, UserPrincipal currentUser) {
        return new RepositoryAccessContext(repository.getInstallationId(), currentUser.getId());
    }

    private String safeFailureReason(RuntimeException exception) {
        if (exception instanceof InvalidRequestException) {
            return exception.getMessage();
        }
        return "Repository ingestion failed; inspect the audit log for the revision";
    }

    private record ClassifiedEntry(RepositoryTreeEntry entry, RepositoryArtifactKind kind) {}
}

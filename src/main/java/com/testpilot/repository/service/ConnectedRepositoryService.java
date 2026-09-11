package com.testpilot.repository.service;

import com.testpilot.auth.security.UserPrincipal;
import com.testpilot.common.exception.ResourceNotFoundException;
import com.testpilot.project.service.ProjectService;
import com.testpilot.repository.connector.*;
import com.testpilot.repository.dto.*;
import com.testpilot.repository.entity.ConnectedRepository;
import com.testpilot.repository.repository.ConnectedRepositoryRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ConnectedRepositoryService {

    private final ConnectedRepositoryRepository repositoryStore;
    private final RepositoryConnectorRegistry connectorRegistry;
    private final ProjectService projectService;
    private final GitHubInstallationService installationService;
    private final RepositoryIngestionService ingestionService;
    private final RepositoryAuditService auditService;

    public ConnectedRepositoryService(
            ConnectedRepositoryRepository repositoryStore,
            RepositoryConnectorRegistry connectorRegistry,
            ProjectService projectService,
            GitHubInstallationService installationService,
            RepositoryIngestionService ingestionService,
            RepositoryAuditService auditService) {
        this.repositoryStore = repositoryStore;
        this.connectorRegistry = connectorRegistry;
        this.projectService = projectService;
        this.installationService = installationService;
        this.ingestionService = ingestionService;
        this.auditService = auditService;
    }

    public RepositoryConnectionResult connect(
            Long projectId,
            ConnectRepositoryRequest request,
            UserPrincipal currentUser) {
        projectService.findProjectAndVerifyWriteAccess(projectId, currentUser);
        RepositoryCoordinates coordinates = new RepositoryCoordinates(request.owner(), request.name());
        RepositoryAccessContext accessContext = new RepositoryAccessContext(request.installationId(), currentUser.getId());
        if (request.transport() == RepositoryTransport.GITHUB_APP_REST) {
            installationService.requireGrant(currentUser.getId(), request.installationId());
        }

        try {
            RepositoryConnector connector = connectorRegistry.require(request.transport());
            RemoteRepositoryMetadata metadata = connector.getRepository(coordinates, accessContext);
            String revision = request.revision() == null || request.revision().isBlank()
                    ? metadata.defaultBranch()
                    : request.revision();
            String commitSha = connector.resolveRevision(coordinates, revision, accessContext);
            String scope = request.transport() == RepositoryTransport.GITHUB_APP_REST
                    ? "installation:" + request.installationId() + ":repository:" + request.owner() + "/" + request.name()
                    : "mcp-allowlist:repository:" + request.owner() + "/" + request.name();

            ConnectedRepository entity = saveConnection(
                    projectId, request, metadata, commitSha, scope);
            auditService.record(
                    entity.getId(), currentUser.getId(), request.installationId(), request.owner(), request.name(),
                    "CONNECT_REPOSITORY", "SUCCESS", "commit=" + commitSha);
            RepositoryIngestionResponse ingestion = ingestionService.ingest(entity.getId(), commitSha, currentUser);
            return new RepositoryConnectionResult(ConnectedRepositoryResponse.fromEntity(entity), ingestion);
        } catch (AccessDeniedException e) {
            auditService.record(
                    null, currentUser.getId(), request.installationId(), request.owner(), request.name(),
                    "CONNECT_REPOSITORY", "ACCESS_DENIED", "Repository was outside the authorized installation scope");
            throw e;
        }
    }

    public List<RemoteRepositoryResponse> discover(
            RepositoryTransport transport,
            Long installationId,
            UserPrincipal currentUser) {
        if (transport == RepositoryTransport.GITHUB_APP_REST) {
            installationService.requireGrant(currentUser.getId(), installationId);
        }
        return connectorRegistry.require(transport)
                .discoverRepositories(new RepositoryAccessContext(installationId, currentUser.getId()))
                .stream()
                .map(RemoteRepositoryResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ConnectedRepositoryResponse getByProject(Long projectId, UserPrincipal currentUser) {
        projectService.findProjectAndVerifyReadAccess(projectId, currentUser);
        ConnectedRepository entity = repositoryStore.findByProjectId(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("No repository is connected to project: " + projectId));
        return ConnectedRepositoryResponse.fromEntity(entity);
    }

    @Transactional
    public void disconnect(Long repositoryId, UserPrincipal currentUser) {
        ConnectedRepository entity = repositoryStore.findById(repositoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Connected repository not found: " + repositoryId));
        projectService.findProjectAndVerifyWriteAccess(entity.getProjectId(), currentUser);
        entity.disconnect();
        repositoryStore.save(entity);
        auditService.record(
                entity.getId(), currentUser.getId(), entity.getInstallationId(), entity.getOwner(), entity.getName(),
                "DISCONNECT_REPOSITORY", "SUCCESS", null);
    }

    @Transactional
    protected ConnectedRepository saveConnection(
            Long projectId,
            ConnectRepositoryRequest request,
            RemoteRepositoryMetadata metadata,
            String commitSha,
            String scope) {
        ConnectedRepository entity = repositoryStore.findByProjectId(projectId)
                .orElseGet(() -> new ConnectedRepository(
                        projectId, request.transport(), metadata.owner(), metadata.name(), metadata.defaultBranch(),
                        commitSha, request.installationId(), scope));
        if (entity.getId() != null) {
            entity.reconnect(
                    request.transport(), metadata.owner(), metadata.name(), metadata.defaultBranch(),
                    commitSha, request.installationId(), scope);
        }
        return repositoryStore.save(entity);
    }
}

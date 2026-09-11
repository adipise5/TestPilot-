package com.testpilot.repository.dto;

import com.testpilot.repository.connector.RepositoryTransport;
import com.testpilot.repository.entity.ConnectedRepository;
import com.testpilot.repository.entity.RepositoryConnectionStatus;
import com.testpilot.repository.entity.RepositoryProvider;

import java.time.LocalDateTime;

public record ConnectedRepositoryResponse(
        Long id,
        Long projectId,
        RepositoryProvider provider,
        RepositoryTransport transport,
        String owner,
        String name,
        String defaultBranch,
        String selectedCommitSha,
        Long installationId,
        String installationScope,
        RepositoryConnectionStatus status,
        LocalDateTime connectedAt,
        LocalDateTime updatedAt
) {
    public static ConnectedRepositoryResponse fromEntity(ConnectedRepository repository) {
        return new ConnectedRepositoryResponse(
                repository.getId(), repository.getProjectId(), repository.getProvider(), repository.getTransport(),
                repository.getOwner(), repository.getName(), repository.getDefaultBranch(),
                repository.getSelectedCommitSha(), repository.getInstallationId(), repository.getInstallationScope(),
                repository.getStatus(), repository.getConnectedAt(), repository.getUpdatedAt());
    }
}

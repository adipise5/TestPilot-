package com.testpilot.repository.dto;

import com.testpilot.repository.entity.BuildSystem;
import com.testpilot.repository.entity.RepositoryIngestion;
import com.testpilot.repository.entity.RepositoryIngestionStatus;

import java.time.LocalDateTime;

public record RepositoryIngestionResponse(
        Long id,
        Long connectedRepositoryId,
        String commitSha,
        RepositoryIngestionStatus status,
        BuildSystem buildSystem,
        int fileCount,
        String catalogHash,
        String failureReason,
        LocalDateTime startedAt,
        LocalDateTime completedAt
) {
    public static RepositoryIngestionResponse fromEntity(RepositoryIngestion ingestion) {
        return new RepositoryIngestionResponse(
                ingestion.getId(), ingestion.getConnectedRepositoryId(), ingestion.getCommitSha(),
                ingestion.getStatus(), ingestion.getBuildSystem(), ingestion.getFileCount(),
                ingestion.getCatalogHash(), ingestion.getFailureReason(), ingestion.getStartedAt(),
                ingestion.getCompletedAt());
    }
}

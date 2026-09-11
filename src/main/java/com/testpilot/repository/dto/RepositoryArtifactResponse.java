package com.testpilot.repository.dto;

import com.testpilot.repository.entity.RepositoryArtifact;
import com.testpilot.repository.entity.RepositoryArtifactKind;

public record RepositoryArtifactResponse(
        Long id,
        Long ingestionId,
        String path,
        String objectSha,
        String contentHash,
        RepositoryArtifactKind kind,
        long sizeBytes,
        String content
) {
    public static RepositoryArtifactResponse fromEntity(RepositoryArtifact artifact) {
        return new RepositoryArtifactResponse(
                artifact.getId(), artifact.getIngestionId(), artifact.getPath(), artifact.getObjectSha(),
                artifact.getContentHash(), artifact.getKind(), artifact.getSizeBytes(), artifact.getContent());
    }
}

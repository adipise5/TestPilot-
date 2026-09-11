package com.testpilot.repository.dto;

import com.testpilot.repository.connector.RemoteRepositoryMetadata;

public record RemoteRepositoryResponse(String owner, String name, String defaultBranch, String visibility) {
    public static RemoteRepositoryResponse from(RemoteRepositoryMetadata metadata) {
        return new RemoteRepositoryResponse(
                metadata.owner(), metadata.name(), metadata.defaultBranch(), metadata.visibility());
    }
}

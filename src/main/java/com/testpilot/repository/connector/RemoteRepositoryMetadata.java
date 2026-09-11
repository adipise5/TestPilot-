package com.testpilot.repository.connector;

public record RemoteRepositoryMetadata(
        String owner,
        String name,
        String defaultBranch,
        String visibility
) {}

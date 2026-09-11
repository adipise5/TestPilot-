package com.testpilot.repository.connector;

public record RepositoryFileContent(
        String path,
        String objectSha,
        byte[] content
) {}

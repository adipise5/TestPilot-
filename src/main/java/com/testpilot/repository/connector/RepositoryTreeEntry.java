package com.testpilot.repository.connector;

public record RepositoryTreeEntry(
        String path,
        String objectSha,
        long size,
        String type
) {
    public boolean isFile() {
        return "blob".equals(type) || "file".equals(type);
    }
}

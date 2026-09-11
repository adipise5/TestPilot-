package com.testpilot.repository.dto;

public record RepositoryConnectionResult(
        ConnectedRepositoryResponse repository,
        RepositoryIngestionResponse ingestion
) {}

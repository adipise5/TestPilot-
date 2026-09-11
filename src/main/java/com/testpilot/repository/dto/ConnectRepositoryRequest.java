package com.testpilot.repository.dto;

import com.testpilot.repository.connector.RepositoryTransport;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ConnectRepositoryRequest(
        @NotNull(message = "Repository transport is required")
        RepositoryTransport transport,

        @NotBlank(message = "Repository owner is required")
        @Size(max = 100)
        String owner,

        @NotBlank(message = "Repository name is required")
        @Size(max = 100)
        String name,

        @Size(max = 255)
        String revision,

        Long installationId
) {}

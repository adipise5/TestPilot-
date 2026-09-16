package com.testpilot.repository.dto;

import com.testpilot.repository.connector.RepositoryTransport;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ConnectRepositoryRequest(
        @NotNull(message = "Repository transport is required")
        RepositoryTransport transport,

        @Size(max = 100)
        String owner,

        @Size(max = 100)
        String name,

        @Size(max = 255)
        String revision,

        Long installationId,
        @Size(max = 2048) String repositoryUrl
) {
    public ConnectRepositoryRequest(RepositoryTransport transport, String owner, String name,
                                    String revision, Long installationId) {
        this(transport, owner, name, revision, installationId, null);
    }

    public ConnectRepositoryRequest normalized() {
        var policy = new com.testpilot.repository.github.GitHubCoordinatesPolicy();
        var coordinates = repositoryUrl == null || repositoryUrl.isBlank()
                ? policy.validate(new com.testpilot.repository.connector.RepositoryCoordinates(owner, name))
                : policy.fromUrl(repositoryUrl);
        if (repositoryUrl != null && !repositoryUrl.isBlank()
                && ((owner != null && !owner.equalsIgnoreCase(coordinates.owner()))
                || (name != null && !name.equalsIgnoreCase(coordinates.name())))) {
            throw new com.testpilot.common.exception.InvalidRequestException("Repository URL and owner/name disagree");
        }
        return new ConnectRepositoryRequest(transport, coordinates.owner(), coordinates.name(), revision, installationId);
    }
}

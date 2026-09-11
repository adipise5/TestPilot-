package com.testpilot.repository.connector;

import java.util.List;

public interface RepositoryConnector {

    RepositoryTransport transport();

    List<RemoteRepositoryMetadata> discoverRepositories(RepositoryAccessContext accessContext);

    RemoteRepositoryMetadata getRepository(
            RepositoryCoordinates coordinates,
            RepositoryAccessContext accessContext);

    String resolveRevision(
            RepositoryCoordinates coordinates,
            String revision,
            RepositoryAccessContext accessContext);

    List<RepositoryTreeEntry> listTree(
            RepositoryCoordinates coordinates,
            String commitSha,
            RepositoryAccessContext accessContext);

    RepositoryFileContent readFile(
            RepositoryCoordinates coordinates,
            String commitSha,
            RepositoryTreeEntry entry,
            RepositoryAccessContext accessContext);
}

package com.testpilot.repository.repository;

import com.testpilot.repository.entity.ConnectedRepository;
import com.testpilot.repository.entity.RepositoryConnectionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConnectedRepositoryRepository extends JpaRepository<ConnectedRepository, Long> {
    Optional<ConnectedRepository> findByProjectId(Long projectId);
    List<ConnectedRepository> findByInstallationIdAndStatus(Long installationId, RepositoryConnectionStatus status);
    Optional<ConnectedRepository> findByInstallationIdAndOwnerIgnoreCaseAndNameIgnoreCase(
            Long installationId, String owner, String name);
}

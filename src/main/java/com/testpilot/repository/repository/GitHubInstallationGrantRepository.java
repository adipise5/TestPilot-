package com.testpilot.repository.repository;

import com.testpilot.repository.entity.GitHubInstallationGrant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GitHubInstallationGrantRepository extends JpaRepository<GitHubInstallationGrant, Long> {
    boolean existsByUserIdAndInstallationIdAndActiveTrue(Long userId, Long installationId);
    Optional<GitHubInstallationGrant> findByInstallationId(Long installationId);
    List<GitHubInstallationGrant> findByInstallationIdAndActiveTrue(Long installationId);
}

package com.testpilot.repository.repository;

import com.testpilot.repository.entity.GitHubInstallationState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GitHubInstallationStateRepository extends JpaRepository<GitHubInstallationState, Long> {
    Optional<GitHubInstallationState> findByStateHash(String stateHash);
}

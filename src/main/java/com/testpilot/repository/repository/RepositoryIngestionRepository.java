package com.testpilot.repository.repository;

import com.testpilot.repository.entity.RepositoryIngestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RepositoryIngestionRepository extends JpaRepository<RepositoryIngestion, Long> {
    Optional<RepositoryIngestion> findByConnectedRepositoryIdAndCommitSha(Long repositoryId, String commitSha);
    List<RepositoryIngestion> findByConnectedRepositoryIdOrderByStartedAtDesc(Long repositoryId);
}

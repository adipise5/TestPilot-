package com.testpilot.repository.repository;

import com.testpilot.repository.entity.RepositoryArtifact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RepositoryArtifactRepository extends JpaRepository<RepositoryArtifact, Long> {
    List<RepositoryArtifact> findByIngestionIdOrderByPath(Long ingestionId);
    void deleteByIngestionId(Long ingestionId);
}

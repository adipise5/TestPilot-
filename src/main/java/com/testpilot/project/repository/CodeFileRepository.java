package com.testpilot.project.repository;

import com.testpilot.project.entity.CodeFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CodeFileRepository extends JpaRepository<CodeFile, Long> {
    List<CodeFile> findByProjectId(Long projectId);
    Optional<CodeFile> findByIdAndProjectId(Long id, Long projectId);
}

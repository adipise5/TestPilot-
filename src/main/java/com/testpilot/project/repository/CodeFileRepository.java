package com.testpilot.project.repository;

import com.testpilot.project.entity.CodeFile;
import com.testpilot.project.entity.CodeFileOrigin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CodeFileRepository extends JpaRepository<CodeFile, Long> {
    List<CodeFile> findByProjectId(Long projectId);
    List<CodeFile> findByProjectIdAndOrigin(Long projectId, CodeFileOrigin origin);
    List<CodeFile> findByProjectIdAndOriginAndCommitSha(Long projectId, CodeFileOrigin origin, String commitSha);
    void deleteByProjectIdAndOrigin(Long projectId, CodeFileOrigin origin);
    Optional<CodeFile> findByIdAndProjectId(Long id, Long projectId);
    boolean existsByProjectIdAndFilePath(Long projectId, String filePath);
}

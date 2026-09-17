package com.testpilot.testing.execution.sandbox;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DraftExecutionRepository extends JpaRepository<DraftExecution, Long> {
    Optional<DraftExecution> findByDraftIdAndProjectId(Long draftId, Long projectId);
    List<DraftExecution> findByStatusAndStartedAtBefore(String status, LocalDateTime before);
}

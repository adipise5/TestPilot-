package com.testpilot.rag.repository;

import com.testpilot.rag.entity.RagRetrievalTrace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RagRetrievalTraceRepository extends JpaRepository<RagRetrievalTrace, Long> {
    Optional<RagRetrievalTrace> findByIdAndTenantIdAndProjectId(Long id, Long tenantId, Long projectId);
    List<RagRetrievalTrace> findByTestRunIdOrderByCreatedAtAsc(Long testRunId);
}

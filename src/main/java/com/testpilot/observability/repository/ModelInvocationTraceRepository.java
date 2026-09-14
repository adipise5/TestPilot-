package com.testpilot.observability.repository;

import com.testpilot.observability.entity.ModelInvocationTrace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ModelInvocationTraceRepository extends JpaRepository<ModelInvocationTrace, Long> {
    List<ModelInvocationTrace> findByTestRunIdOrderByCreatedAtAsc(Long testRunId);
}

package com.testpilot.testing.workflow.repository;

import com.testpilot.testing.workflow.entity.WorkflowStepExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkflowStepExecutionRepository extends JpaRepository<WorkflowStepExecution, Long> {
    Optional<WorkflowStepExecution> findByWorkflowRunIdAndIdempotencyKey(Long workflowRunId, String idempotencyKey);
    List<WorkflowStepExecution> findByWorkflowRunIdOrderByStartedAtAsc(Long workflowRunId);
}

package com.testpilot.testing.workflow.repository;

import com.testpilot.testing.workflow.entity.WorkflowRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, Long> {
    Optional<WorkflowRun> findByTestRunId(Long testRunId);
    Optional<WorkflowRun> findByThreadId(String threadId);
}

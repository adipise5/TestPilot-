package com.testpilot.testing.workflow.service;

import com.testpilot.common.exception.InvalidRequestException;
import com.testpilot.testing.workflow.entity.WorkflowStepExecution;
import com.testpilot.testing.workflow.entity.WorkflowStepStatus;
import com.testpilot.testing.workflow.repository.WorkflowStepExecutionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowStepPersistenceService {

    private final WorkflowStepExecutionRepository repository;

    public WorkflowStepPersistenceService(WorkflowStepExecutionRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BeginStepResult begin(
            Long workflowRunId,
            String node,
            String idempotencyKey,
            String inputHash) {
        return repository.findByWorkflowRunIdAndIdempotencyKey(workflowRunId, idempotencyKey)
                .map(existing -> resumeExisting(existing, node, inputHash))
                .orElseGet(() -> new BeginStepResult(
                        repository.save(new WorkflowStepExecution(
                                workflowRunId, node, idempotencyKey, inputHash)),
                        false));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(Long stepId, String outputJson) {
        WorkflowStepExecution step = repository.findById(stepId)
                .orElseThrow(() -> new InvalidRequestException("Workflow step no longer exists"));
        step.complete(outputJson);
        repository.save(step);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Long stepId, String error) {
        repository.findById(stepId).ifPresent(step -> {
            step.fail(truncate(error == null ? "Workflow tool failed" : error));
            repository.save(step);
        });
    }

    private BeginStepResult resumeExisting(
            WorkflowStepExecution existing,
            String node,
            String inputHash) {
        if (!existing.getNodeName().equals(node) || !existing.getInputHash().equals(inputHash)) {
            throw new InvalidRequestException("Idempotency key was reused with different workflow input");
        }
        if (existing.getStatus() == WorkflowStepStatus.COMPLETED) {
            return new BeginStepResult(existing, true);
        }
        if (existing.getStatus() == WorkflowStepStatus.RUNNING) {
            throw new InvalidRequestException("Workflow step is already running");
        }
        existing.retry();
        return new BeginStepResult(repository.save(existing), false);
    }

    private String truncate(String value) {
        return value.substring(0, Math.min(500, value.length()));
    }

    public record BeginStepResult(WorkflowStepExecution step, boolean replayed) {}
}

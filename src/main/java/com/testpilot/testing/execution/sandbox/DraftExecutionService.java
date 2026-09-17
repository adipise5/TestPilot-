package com.testpilot.testing.execution.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.auth.security.UserPrincipal;
import com.testpilot.common.exception.ResourceNotFoundException;
import com.testpilot.project.service.ProjectService;
import com.testpilot.testing.generation.TestPlanningService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;

@Service
public class DraftExecutionService {
    private final DraftExecutionRepository store;
    private final ProjectService projects;
    private final TestPlanningService planning;
    private final SecureContainerExecutor executor;
    private final ObjectMapper mapper;
    public DraftExecutionService(DraftExecutionRepository store, ProjectService projects, TestPlanningService planning,
                                 SecureContainerExecutor executor, ObjectMapper mapper) {
        this.store = store; this.projects = projects; this.planning = planning; this.executor = executor; this.mapper = mapper;
    }
    public record Response(Long draftId, String status, LocalDateTime startedAt, SandboxResult result) {}
    public Response get(Long projectId, Long draftId, UserPrincipal user) {
        projects.findProjectAndVerifyReadAccess(projectId, user);
        return response(store.findByDraftIdAndProjectId(draftId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("No execution for this draft")));
    }
    public Response execute(Long projectId, Long draftId, UserPrincipal user) {
        // Authorization, snapshot matching and fresh validation all precede process launch.
        SandboxRequest input = planning.executionInput(projectId, draftId, user);
        var existing = store.findByDraftIdAndProjectId(draftId, projectId);
        if (existing.isPresent() && existing.get().getStatus().equals("RUNNING")) return response(existing.get());
        DraftExecution execution = existing.orElseGet(() -> new DraftExecution(projectId, draftId));
        if (existing.isPresent()) execution.restart();
        try { execution = store.saveAndFlush(execution); }
        catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException raced) {
            return response(store.findByDraftIdAndProjectId(draftId, projectId).orElseThrow());
        }
        var result = executor.execute(input, execution.getContainerName(), () -> false, () -> {});
        execution.finish(encode(result));
        try { return response(store.saveAndFlush(execution)); }
        catch (ObjectOptimisticLockingFailureException recovered) {
            return response(store.findByDraftIdAndProjectId(draftId, projectId).orElseThrow());
        }
    }
    @Scheduled(fixedDelay = 30000)
    public void recoverLostExecutions() {
        for (var execution : store.findByStatusAndStartedAtBefore("RUNNING", LocalDateTime.now().minusSeconds(100))) {
            executor.cleanup(execution.getContainerName());
            execution.finish(encode(SandboxResult.failure("INFRASTRUCTURE_FAILURE", "Worker/server was interrupted; scoped container cleanup requested. Retry explicitly.")));
            try { store.saveAndFlush(execution); }
            catch (ObjectOptimisticLockingFailureException ignored) { }
        }
    }
    private String encode(SandboxResult result) {
        try { return mapper.writeValueAsString(result); }
        catch (java.io.IOException ex) { throw new IllegalStateException("Unable to save execution result", ex); }
    }
    private Response response(DraftExecution execution) {
        try { return new Response(execution.getDraftId(), execution.getStatus(), execution.getStartedAt(),
                execution.getResultJson() == null ? null : mapper.readValue(execution.getResultJson(), SandboxResult.class)); }
        catch (java.io.IOException ex) { throw new IllegalStateException("Invalid stored execution result", ex); }
    }
}

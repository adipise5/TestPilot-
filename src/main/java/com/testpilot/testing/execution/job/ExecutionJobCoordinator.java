package com.testpilot.testing.execution.job;

import com.testpilot.project.entity.CodeFile;
import com.testpilot.repository.entity.RepositoryArtifact;
import com.testpilot.testing.entity.GeneratedTest;
import com.testpilot.testing.execution.TestExecutionOutcome;
import com.testpilot.testing.execution.TestExecutionOutcomeType;
import com.testpilot.testing.execution.TestExecutionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

@Service
public class ExecutionJobCoordinator {

    private final ExecutionJobPersistenceService persistence;
    private final TestExecutionService executionService;
    private final Duration waitTimeout;

    public ExecutionJobCoordinator(
            ExecutionJobPersistenceService persistence,
            TestExecutionService executionService,
            @Value("${testpilot.execution.await-seconds:240}") long waitSeconds) {
        this.persistence = persistence;
        this.executionService = executionService;
        this.waitTimeout = Duration.ofSeconds(Math.max(10, waitSeconds));
    }

    public TestExecutionOutcome execute(
            Long testRunId,
            List<CodeFile> sourceFiles,
            List<GeneratedTest> generatedTests) {
        return execute(testRunId, (cancelled, heartbeat) -> executionService.executeTests(
                testRunId, sourceFiles, generatedTests, cancelled, heartbeat));
    }

    public TestExecutionOutcome executeCatalog(
            Long testRunId,
            List<RepositoryArtifact> catalog,
            List<GeneratedTest> generatedTests) {
        return execute(testRunId, (cancelled, heartbeat) -> executionService.executeCatalog(
                testRunId, catalog, generatedTests, cancelled, heartbeat));
    }

    private TestExecutionOutcome execute(Long testRunId, ExecutionAttempt attempt) {
        ExecutionJob job = persistence.submit(testRunId);
        Long jobId = job.getId();
        long deadline = System.nanoTime() + waitTimeout.toNanos();

        while (System.nanoTime() < deadline) {
            job = persistence.get(jobId);
            if (job.isTerminal()) return persistence.readOutcome(job);

            String workerId = "worker-" + UUID.randomUUID();
            if (!persistence.claim(jobId, workerId)) {
                pause();
                continue;
            }

            TestExecutionOutcome outcome = attempt.run(
                    () -> persistence.isCancellationRequested(jobId),
                    () -> persistence.heartbeat(jobId, workerId));

            if (outcome.type() == TestExecutionOutcomeType.INFRASTRUCTURE_FAILURE) {
                if (persistence.retryOrFail(jobId, workerId, outcome)) continue;
            } else {
                persistence.complete(jobId, workerId, outcome);
            }
            return persistence.readOutcome(persistence.get(jobId));
        }

        return new TestExecutionOutcome(
                TestExecutionOutcomeType.INFRASTRUCTURE_FAILURE,
                List.of(),
                null,
                "Timed out waiting for a durable execution-job lease");
    }

    private void pause() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for execution job", e);
        }
    }

    @FunctionalInterface
    private interface ExecutionAttempt {
        TestExecutionOutcome run(BooleanSupplier cancellationRequested, Runnable heartbeat);
    }
}

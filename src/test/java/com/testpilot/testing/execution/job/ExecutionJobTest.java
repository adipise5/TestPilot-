package com.testpilot.testing.execution.job;

import com.testpilot.testing.execution.TestExecutionOutcome;
import com.testpilot.testing.execution.TestExecutionOutcomeType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionJobTest {

    @Test
    void leaseExpiryRecoversWithoutDuplicatingCompletedWork() {
        ExecutionJob job = new ExecutionJob(42L, 2);
        LocalDateTime now = LocalDateTime.now();

        assertTrue(job.claim("lost-worker", now, now.minusSeconds(1)));
        job.markRunning("lost-worker");
        job.recoverExpired(now);
        assertEquals(ExecutionJobStatus.QUEUED, job.getStatus());

        assertTrue(job.claim("replacement-worker", now, now.plusSeconds(30)));
        job.markRunning("replacement-worker");
        job.complete("replacement-worker", new TestExecutionOutcome(
                TestExecutionOutcomeType.SUCCESS, List.of(), 0, "ok"), "{}");

        assertEquals(ExecutionJobStatus.COMPLETED, job.getStatus());
        assertEquals(2, job.getAttempts());
        assertFalse(job.claim("duplicate-worker", now, now.plusSeconds(30)));
    }

    @Test
    void queuedCancellationIsTerminalBeforeCodeRuns() {
        ExecutionJob job = new ExecutionJob(43L, 3);
        job.requestCancellation();
        assertEquals(ExecutionJobStatus.CANCELLED, job.getStatus());
        assertTrue(job.isTerminal());
        assertFalse(job.claim("worker", LocalDateTime.now(), LocalDateTime.now().plusSeconds(30)));
    }

    @Test
    void finalExpiredLeaseBecomesTypedInfrastructureFailure() {
        ExecutionJob job = new ExecutionJob(44L, 1);
        LocalDateTime now = LocalDateTime.now();
        assertTrue(job.claim("lost-worker", now, now.minusSeconds(1)));
        job.markRunning("lost-worker");
        job.recoverExpired(now);

        assertEquals(ExecutionJobStatus.FAILED, job.getStatus());
        assertEquals(TestExecutionOutcomeType.INFRASTRUCTURE_FAILURE, job.getOutcome());
        assertEquals("worker-lost", job.getIsolationBackend());
    }
}

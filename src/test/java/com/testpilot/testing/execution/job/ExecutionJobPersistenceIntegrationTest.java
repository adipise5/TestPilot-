package com.testpilot.testing.execution.job;

import com.testpilot.testing.entity.TestResult;
import com.testpilot.testing.entity.TestResultStatus;
import com.testpilot.testing.execution.TestExecutionMetrics;
import com.testpilot.testing.execution.TestExecutionOutcome;
import com.testpilot.testing.execution.TestExecutionOutcomeType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("h2")
class ExecutionJobPersistenceIntegrationTest {

    @Autowired private ExecutionJobPersistenceService jobs;

    @Test
    void submissionAndCompletionAreIdempotentAcrossTransactions() {
        var first = jobs.submit(990_001L);
        var repeated = jobs.submit(990_001L);
        assertEquals(first.getId(), repeated.getId());

        assertTrue(jobs.claim(first.getId(), "worker-a"));
        jobs.heartbeat(first.getId(), "worker-a");
        jobs.complete(first.getId(), "worker-a", new TestExecutionOutcome(
                TestExecutionOutcomeType.SUCCESS,
                List.of(new TestResult(990_001L, "ExampleTest.passes", TestResultStatus.PASSED, null, null, 0.01)),
                0,
                "bounded output",
                "container",
                new TestExecutionMetrics(87.5, 70.0, "COLLECTED", "COLLECTED")));

        var completed = jobs.get(first.getId());
        assertEquals(ExecutionJobStatus.COMPLETED, completed.getStatus());
        assertEquals(1, completed.getAttempts());
        assertEquals(87.5, completed.getLineCoveragePercent());
        assertEquals("ExampleTest.passes", jobs.readOutcome(completed).results().get(0).getTestName());
        assertFalse(jobs.claim(first.getId(), "duplicate-worker"));
    }
}

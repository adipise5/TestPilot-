package com.testpilot.testing.execution.job;

import com.testpilot.testing.execution.DurableTestExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "testpilot.execution.recovery-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ExecutionJobRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExecutionJobRecoveryScheduler.class);
    private final ExecutionJobPersistenceService jobs;
    private final DurableTestExecutionService executions;

    public ExecutionJobRecoveryScheduler(
            ExecutionJobPersistenceService jobs,
            DurableTestExecutionService executions) {
        this.jobs = jobs;
        this.executions = executions;
    }

    @Scheduled(initialDelayString = "${testpilot.execution.recovery-initial-delay-ms:5000}",
            fixedDelayString = "${testpilot.execution.recovery-delay-ms:5000}")
    public void recoverAndDispatch() {
        var recovered = jobs.recoverExpiredLeases();
        if (!recovered.isEmpty()) log.warn("Recovered {} expired execution-job leases", recovered.size());
        for (Long jobId : recovered) {
            try {
                executions.recoverJob(jobId);
            } catch (Exception e) {
                log.error("Unable to finalize recovered execution job {}", jobId, e);
            }
        }
        for (Long jobId : jobs.queuedJobIds()) {
            try {
                executions.recoverJob(jobId);
            } catch (Exception e) {
                log.error("Unable to dispatch durable execution job {}", jobId, e);
            }
        }
    }
}

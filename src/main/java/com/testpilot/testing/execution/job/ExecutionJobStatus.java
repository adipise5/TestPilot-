package com.testpilot.testing.execution.job;

public enum ExecutionJobStatus {
    QUEUED,
    LEASED,
    RUNNING,
    RETRY_WAIT,
    COMPLETED,
    FAILED,
    CANCELLED
}

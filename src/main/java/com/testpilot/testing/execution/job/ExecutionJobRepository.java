package com.testpilot.testing.execution.job;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ExecutionJobRepository extends JpaRepository<ExecutionJob, Long> {
    Optional<ExecutionJob> findByTestRunId(Long testRunId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from ExecutionJob j where j.id = :id")
    Optional<ExecutionJob> findByIdForUpdate(Long id);

    List<ExecutionJob> findByStatusInOrderByCreatedAtAsc(List<ExecutionJobStatus> statuses);

    List<ExecutionJob> findByStatusInAndLeaseExpiresAtBefore(
            List<ExecutionJobStatus> statuses,
            LocalDateTime deadline);
}

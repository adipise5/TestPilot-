package com.testpilot.delivery.repository;

import com.testpilot.delivery.entity.DeliveryRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface DeliveryRecordRepository extends JpaRepository<DeliveryRecord, Long> {
    Optional<DeliveryRecord> findByTestRunId(Long testRunId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DeliveryRecord d where d.id = :id")
    Optional<DeliveryRecord> findByIdForUpdate(Long id);
}

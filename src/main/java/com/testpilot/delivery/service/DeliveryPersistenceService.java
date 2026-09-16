package com.testpilot.delivery.service;

import com.testpilot.common.exception.ResourceNotFoundException;
import com.testpilot.common.exception.InvalidRequestException;
import com.testpilot.delivery.entity.DeliveryRecord;
import com.testpilot.delivery.github.GitHubDeliveryResult;
import com.testpilot.delivery.repository.DeliveryRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeliveryPersistenceService {

    private final DeliveryRecordRepository records;

    public DeliveryPersistenceService(DeliveryRecordRepository records) {
        this.records = records;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DeliveryRecord begin(Long deliveryId, Long actorUserId) {
        DeliveryRecord record = locked(deliveryId);
        try {
            record.beginDelivery(actorUserId);
        } catch (IllegalStateException e) {
            throw new InvalidRequestException(e.getMessage());
        }
        return records.save(record);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DeliveryRecord complete(Long deliveryId, GitHubDeliveryResult result) {
        DeliveryRecord record = locked(deliveryId);
        record.delivered(result.pullRequestNumber(), result.pullRequestUrl(), result.headCommitSha());
        return records.save(record);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DeliveryRecord fail(Long deliveryId, String reason) {
        DeliveryRecord record = locked(deliveryId);
        record.failed(reason);
        return records.save(record);
    }

    private DeliveryRecord locked(Long deliveryId) {
        return records.findByIdForUpdate(deliveryId)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery proposal not found: " + deliveryId));
    }
}

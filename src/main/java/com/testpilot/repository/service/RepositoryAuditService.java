package com.testpilot.repository.service;

import com.testpilot.repository.entity.RepositoryAuditEvent;
import com.testpilot.repository.repository.RepositoryAuditEventRepository;
import org.springframework.stereotype.Service;

@Service
public class RepositoryAuditService {

    private final RepositoryAuditEventRepository repository;

    public RepositoryAuditService(RepositoryAuditEventRepository repository) {
        this.repository = repository;
    }

    public void record(
            Long connectedRepositoryId,
            Long actorUserId,
            Long installationId,
            String owner,
            String repositoryName,
            String action,
            String outcome,
            String detail) {
        repository.save(new RepositoryAuditEvent(
                connectedRepositoryId,
                actorUserId,
                installationId,
                owner,
                repositoryName,
                action,
                outcome,
                detail));
    }
}

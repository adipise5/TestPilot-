package com.testpilot.repository.repository;

import com.testpilot.repository.entity.RepositoryAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RepositoryAuditEventRepository extends JpaRepository<RepositoryAuditEvent, Long> {}

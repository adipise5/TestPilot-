package com.testpilot.failure.repository;

import com.testpilot.failure.entity.FailureAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FailureAnalysisRepository extends JpaRepository<FailureAnalysis, Long> {
    Optional<FailureAnalysis> findByTestResultId(Long testResultId);
}

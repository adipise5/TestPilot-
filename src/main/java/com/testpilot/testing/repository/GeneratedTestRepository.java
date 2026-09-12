package com.testpilot.testing.repository;

import com.testpilot.testing.entity.GeneratedTest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GeneratedTestRepository extends JpaRepository<GeneratedTest, Long> {
    List<GeneratedTest> findByTestRunId(Long testRunId);
    Optional<GeneratedTest> findByTestRunIdAndTestClass(Long testRunId, String testClass);
}

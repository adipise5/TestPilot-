package com.testpilot.testing.generation;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TestDraftRepository extends JpaRepository<TestDraft, Long> {
    List<TestDraft> findTop100ByProjectIdOrderByIdDesc(Long projectId);
}

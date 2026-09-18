package com.testpilot.review;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.*;
public interface CodeReviewRepository extends JpaRepository<CodeReview, Long> {
    List<CodeReview> findTop50ByProjectIdOrderByIdDesc(Long projectId);
    Optional<CodeReview> findByIdAndProjectId(Long id, Long projectId);
    List<CodeReview> findByStatusAndStartedAtBefore(String status, LocalDateTime before);
}

package com.testpilot.review;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "code_reviews", indexes = @Index(name = "ix_code_review_project", columnList = "project_id,id"))
public class CodeReview {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Version private Long version;
    @Column(name = "project_id", nullable = false) private Long projectId;
    @Column(nullable = false, length = 64) private String snapshotId;
    @Column(nullable = false) private String status;
    @Column(nullable = false) private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    @Column(columnDefinition = "TEXT", nullable = false) private String reportJson;
    protected CodeReview() {}
    public CodeReview(Long projectId, String snapshotId, String reportJson) {
        this.projectId = projectId; this.snapshotId = snapshotId; this.reportJson = reportJson;
        this.status = "RUNNING"; this.startedAt = LocalDateTime.now();
    }
    public void finish(String status, String report) { this.status = status; this.reportJson = report; this.completedAt = LocalDateTime.now(); }
    public Long getId() { return id; }
    public Long getProjectId() { return projectId; }
    public String getSnapshotId() { return snapshotId; }
    public String getStatus() { return status; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public String getReportJson() { return reportJson; }
}

package com.testpilot.testing.execution.sandbox;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "draft_executions", uniqueConstraints = @UniqueConstraint(columnNames = "draft_id"))
public class DraftExecution {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Version private Long version;
    @Column(name = "draft_id", nullable = false) private Long draftId;
    @Column(nullable = false) private Long projectId;
    @Column(nullable = false) private String status;
    @Column(nullable = false) private String containerName;
    @Column(nullable = false) private LocalDateTime startedAt;
    @Column(columnDefinition = "TEXT") private String resultJson;
    protected DraftExecution() {}
    public DraftExecution(Long projectId, Long draftId) { this.projectId = projectId; this.draftId = draftId; restart(); }
    public void restart() { status = "RUNNING"; containerName = "testpilot-secure-" + UUID.randomUUID(); startedAt = LocalDateTime.now(); resultJson = null; }
    public void finish(String result) { status = "COMPLETED"; resultJson = result; }
    public Long getId() { return id; }
    public Long getDraftId() { return draftId; }
    public Long getProjectId() { return projectId; }
    public String getStatus() { return status; }
    public String getContainerName() { return containerName; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public String getResultJson() { return resultJson; }
}

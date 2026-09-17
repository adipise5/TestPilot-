package com.testpilot.testing.generation;

import jakarta.persistence.*;

/** Snapshot-bound proposal; execution evidence is stored separately in DraftExecution. */
@Entity
@Table(name = "test_generation_drafts")
public class TestDraft {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long projectId;
    @Column(nullable = false, length = 64) private String snapshotId;
    @Column(nullable = false, columnDefinition = "TEXT") private String resultJson;
    protected TestDraft() {}
    public TestDraft(Long projectId, String snapshotId, String resultJson) {
        this.projectId = projectId; this.snapshotId = snapshotId; this.resultJson = resultJson;
    }
    public Long getId() { return id; }
    public Long getProjectId() { return projectId; }
    public String getSnapshotId() { return snapshotId; }
    public String getResultJson() { return resultJson; }
}

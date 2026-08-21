package com.testpilot.testing.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "generated_tests")
public class GeneratedTest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "test_run_id", nullable = false)
    private Long testRunId;

    @Column(name = "source_file", nullable = false)
    private String sourceFile;

    @Column(name = "test_class", nullable = false)
    private String testClass;

    @Column(name = "test_code", columnDefinition = "TEXT", nullable = false)
    private String testCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public GeneratedTest() {}

    public GeneratedTest(Long testRunId, String sourceFile, String testClass, String testCode) {
        this.testRunId = testRunId;
        this.sourceFile = sourceFile;
        this.testClass = testClass;
        this.testCode = testCode;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getTestRunId() {
        return testRunId;
    }

    public String getSourceFile() {
        return sourceFile;
    }

    public String getTestClass() {
        return testClass;
    }

    public String getTestCode() {
        return testCode;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}

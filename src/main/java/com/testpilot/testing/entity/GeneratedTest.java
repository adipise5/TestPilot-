package com.testpilot.testing.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "generated_tests",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_generated_test_run_class",
                columnNames = {"test_run_id", "test_class"}))
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

    @Enumerated(EnumType.STRING)
    @Column(name = "test_level", nullable = false)
    private TestLevel testLevel;

    @Column(name = "test_code", columnDefinition = "TEXT", nullable = false)
    private String testCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public GeneratedTest() {}

    public GeneratedTest(Long testRunId, String sourceFile, String testClass, String testCode) {
        this(testRunId, sourceFile, testClass, testCode, TestLevel.UNIT);
    }

    public GeneratedTest(
            Long testRunId,
            String sourceFile,
            String testClass,
            String testCode,
            TestLevel testLevel) {
        this.testRunId = testRunId;
        this.sourceFile = sourceFile;
        this.testClass = testClass;
        this.testCode = testCode;
        this.testLevel = testLevel;
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

    public TestLevel getTestLevel() {
        return testLevel;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}

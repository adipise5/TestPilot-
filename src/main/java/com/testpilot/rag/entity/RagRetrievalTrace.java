package com.testpilot.rag.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "rag_retrieval_traces",
        indexes = @Index(name = "idx_rag_trace_scope", columnList = "tenant_id,project_id,commit_sha"))
public class RagRetrievalTrace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "test_run_id")
    private Long testRunId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "commit_sha", nullable = false, length = 80)
    private String commitSha;

    @Column(name = "query_hash", nullable = false, length = 64)
    private String queryHash;

    @Column(name = "query_text", columnDefinition = "TEXT", nullable = false)
    private String queryText;

    @Column(name = "packed_context", columnDefinition = "TEXT", nullable = false)
    private String packedContext;

    @Column(name = "packed_tokens", nullable = false)
    private int packedTokens;

    @Column(name = "query_tokens", nullable = false)
    private int queryTokens;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "dense_candidates", nullable = false)
    private int denseCandidates;

    @Column(name = "lexical_candidates", nullable = false)
    private int lexicalCandidates;

    @Column(name = "embedding_model", nullable = false, length = 120)
    private String embeddingModel;

    @Column(name = "estimated_embedding_cost_usd", nullable = false)
    private double estimatedEmbeddingCostUsd;

    @Column(name = "retrieval_config", length = 500, nullable = false)
    private String retrievalConfig;

    @Column(name = "citations_json", columnDefinition = "TEXT", nullable = false)
    private String citationsJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected RagRetrievalTrace() {}

    public RagRetrievalTrace(
            Long testRunId,
            Long tenantId,
            Long projectId,
            String commitSha,
            String queryHash,
            String queryText,
            String packedContext,
            int packedTokens,
            int queryTokens,
            long latencyMs,
            int denseCandidates,
            int lexicalCandidates,
            String embeddingModel,
            double estimatedEmbeddingCostUsd,
            String retrievalConfig,
            String citationsJson) {
        this.testRunId = testRunId;
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.commitSha = commitSha;
        this.queryHash = queryHash;
        this.queryText = queryText;
        this.packedContext = packedContext;
        this.packedTokens = packedTokens;
        this.queryTokens = queryTokens;
        this.latencyMs = latencyMs;
        this.denseCandidates = denseCandidates;
        this.lexicalCandidates = lexicalCandidates;
        this.embeddingModel = embeddingModel;
        this.estimatedEmbeddingCostUsd = estimatedEmbeddingCostUsd;
        this.retrievalConfig = retrievalConfig;
        this.citationsJson = citationsJson;
    }

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public Long getTestRunId() { return testRunId; }
    public Long getTenantId() { return tenantId; }
    public Long getProjectId() { return projectId; }
    public String getCommitSha() { return commitSha; }
    public String getQueryHash() { return queryHash; }
    public String getQueryText() { return queryText; }
    public String getPackedContext() { return packedContext; }
    public int getPackedTokens() { return packedTokens; }
    public int getQueryTokens() { return queryTokens; }
    public long getLatencyMs() { return latencyMs; }
    public int getDenseCandidates() { return denseCandidates; }
    public int getLexicalCandidates() { return lexicalCandidates; }
    public String getEmbeddingModel() { return embeddingModel; }
    public double getEstimatedEmbeddingCostUsd() { return estimatedEmbeddingCostUsd; }
    public String getRetrievalConfig() { return retrievalConfig; }
    public String getCitationsJson() { return citationsJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}

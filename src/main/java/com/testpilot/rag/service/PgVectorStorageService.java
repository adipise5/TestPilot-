package com.testpilot.rag.service;

import com.testpilot.rag.entity.DocumentChunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.stream.Collectors;

@Service
public class PgVectorStorageService {

    private final JdbcTemplate jdbcTemplate;
    private final String vectorStore;
    private final int dimensions;

    public PgVectorStorageService(
            JdbcTemplate jdbcTemplate,
            @Value("${testpilot.rag.vector-store:pgvector}") String vectorStore,
            @Value("${ai.embedding-dimensions:1536}") int dimensions) {
        this.jdbcTemplate = jdbcTemplate;
        this.vectorStore = vectorStore;
        this.dimensions = dimensions;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        if (!enabled()) return;
        if (dimensions < 1 || dimensions > 2_000) {
            throw new IllegalStateException("pgvector dimensions must be between 1 and 2000");
        }
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbcTemplate.execute("ALTER TABLE document_chunks ADD COLUMN IF NOT EXISTS embedding_vector vector("
                + dimensions + ")");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_rag_chunk_embedding_hnsw "
                + "ON document_chunks USING hnsw (embedding_vector vector_cosine_ops)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_rag_chunk_content_fts "
                + "ON document_chunks USING gin (to_tsvector('simple', content))");
    }

    public void store(DocumentChunk chunk, float[] vector) {
        if (!enabled()) return;
        jdbcTemplate.update(
                "UPDATE document_chunks SET embedding_vector = CAST(? AS vector) WHERE id = ?",
                vectorLiteral(vector),
                chunk.getId());
    }

    public boolean enabled() {
        return "pgvector".equalsIgnoreCase(vectorStore);
    }

    public String vectorLiteral(float[] vector) {
        if (vector.length != dimensions) {
            throw new IllegalArgumentException(
                    "Embedding dimension " + vector.length + " does not match configured pgvector dimension " + dimensions);
        }
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Embedding values must be finite");
        }
        return Arrays.stream(toDoubleArray(vector))
                .mapToObj(Double::toString)
                .collect(Collectors.joining(",", "[", "]"));
    }

    private double[] toDoubleArray(float[] values) {
        double[] result = new double[values.length];
        for (int i = 0; i < values.length; i++) result[i] = values[i];
        return result;
    }
}

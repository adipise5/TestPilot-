package com.testpilot.rag.service;

import com.testpilot.rag.embedding.EmbeddingProvider;
import com.testpilot.rag.entity.DocumentChunk;
import com.testpilot.rag.model.RagScope;
import com.testpilot.rag.repository.DocumentChunkRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ScopedChunkSearchService {

    private final DocumentChunkRepository chunks;
    private final VectorSearchService vectorSearch;
    private final PgVectorStorageService pgVector;
    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingProvider embeddings;

    public ScopedChunkSearchService(
            DocumentChunkRepository chunks,
            VectorSearchService vectorSearch,
            PgVectorStorageService pgVector,
            JdbcTemplate jdbcTemplate,
            EmbeddingProvider embeddings) {
        this.chunks = chunks;
        this.vectorSearch = vectorSearch;
        this.pgVector = pgVector;
        this.jdbcTemplate = jdbcTemplate;
        this.embeddings = embeddings;
    }

    public List<ScoredChunk> dense(RagScope scope, float[] queryVector, int limit) {
        if (pgVector.enabled()) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT id, 1 - (embedding_vector <=> CAST(? AS vector)) AS score
                    FROM document_chunks
                    WHERE tenant_id = ? AND project_id = ? AND commit_sha = ?
                      AND embedding_model = ? AND embedding_vector IS NOT NULL
                    ORDER BY embedding_vector <=> CAST(? AS vector), chunk_key
                    LIMIT ?
                    """,
                    pgVector.vectorLiteral(queryVector), scope.tenantId(), scope.projectId(), scope.commitSha(),
                    embeddings.modelId(), pgVector.vectorLiteral(queryVector), limit);
            return hydrate(rows, "score");
        }

        return scoped(scope).stream()
                .map(chunk -> new ScoredChunk(
                        chunk, vectorSearch.calculateCosineSimilarity(queryVector, chunk.getEmbeddingVector())))
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed()
                        .thenComparing(item -> item.chunk().getChunkKey()))
                .limit(limit)
                .toList();
    }

    public List<ScoredChunk> lexical(RagScope scope, String query, int limit) {
        if (pgVector.enabled()) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT id, ts_rank_cd(
                        to_tsvector('simple', content), plainto_tsquery('simple', ?)) AS score
                    FROM document_chunks
                    WHERE tenant_id = ? AND project_id = ? AND commit_sha = ?
                      AND embedding_model = ?
                      AND to_tsvector('simple', content) @@ plainto_tsquery('simple', ?)
                    ORDER BY score DESC, chunk_key
                    LIMIT ?
                    """,
                    query, scope.tenantId(), scope.projectId(), scope.commitSha(), embeddings.modelId(), query, limit);
            return hydrate(rows, "score");
        }

        Set<String> queryTerms = terms(query);
        return scoped(scope).stream()
                .map(chunk -> new ScoredChunk(chunk, lexicalScore(queryTerms, terms(chunk.getContent()))))
                .filter(item -> item.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed()
                        .thenComparing(item -> item.chunk().getChunkKey()))
                .limit(limit)
                .toList();
    }

    private List<DocumentChunk> scoped(RagScope scope) {
        return chunks.findByTenantIdAndProjectIdAndCommitShaAndEmbeddingModel(
                scope.tenantId(), scope.projectId(), scope.commitSha(), embeddings.modelId());
    }

    private List<ScoredChunk> hydrate(List<Map<String, Object>> rows, String scoreColumn) {
        List<Long> ids = rows.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
        Map<Long, DocumentChunk> byId = chunks.findAllById(ids).stream()
                .collect(Collectors.toMap(DocumentChunk::getId, Function.identity()));
        List<ScoredChunk> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Long id = ((Number) row.get("id")).longValue();
            DocumentChunk chunk = byId.get(id);
            if (chunk != null) result.add(new ScoredChunk(chunk, ((Number) row.get(scoreColumn)).doubleValue()));
        }
        return List.copyOf(result);
    }

    static Set<String> terms(String value) {
        if (value == null) return Set.of();
        return Arrays.stream(value.toLowerCase(Locale.ROOT).split("[^a-z0-9_$]+"))
                .filter(term -> term.length() > 1)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    static double lexicalScore(Set<String> query, Set<String> content) {
        if (query.isEmpty() || content.isEmpty()) return 0;
        long matches = query.stream().filter(content::contains).count();
        return matches / Math.sqrt((double) query.size() * content.size());
    }

    public record ScoredChunk(DocumentChunk chunk, double score) {}
}

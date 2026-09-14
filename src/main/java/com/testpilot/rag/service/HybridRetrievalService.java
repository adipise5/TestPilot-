package com.testpilot.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.rag.dto.RagCitation;
import com.testpilot.rag.dto.RagQueryResult;
import com.testpilot.rag.dto.RagRetrievalResult;
import com.testpilot.rag.embedding.EmbeddingProvider;
import com.testpilot.rag.entity.DocumentChunk;
import com.testpilot.rag.entity.RagRetrievalTrace;
import com.testpilot.rag.model.RagScope;
import com.testpilot.rag.repository.RagRetrievalTraceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class HybridRetrievalService {

    private static final double RRF_K = 60.0;
    private final ScopedChunkSearchService search;
    private final EmbeddingProvider embeddings;
    private final RagRetrievalTraceRepository traces;
    private final ObjectMapper objectMapper;
    private final double relevanceThreshold;
    private final int defaultTokenBudget;
    private final double embeddingCostPerMillionTokens;

    public HybridRetrievalService(
            ScopedChunkSearchService search,
            EmbeddingProvider embeddings,
            RagRetrievalTraceRepository traces,
            ObjectMapper objectMapper,
            @Value("${testpilot.rag.relevance-threshold:0.08}") double relevanceThreshold,
            @Value("${testpilot.rag.token-budget:6000}") int defaultTokenBudget,
            @Value("${testpilot.observability.embedding-cost-per-million:0}") double embeddingCostPerMillionTokens) {
        this.search = search;
        this.embeddings = embeddings;
        this.traces = traces;
        this.objectMapper = objectMapper;
        this.relevanceThreshold = relevanceThreshold;
        this.defaultTokenBudget = defaultTokenBudget;
        this.embeddingCostPerMillionTokens = Math.max(0, embeddingCostPerMillionTokens);
    }

    @Transactional
    public RagRetrievalResult retrieve(
            RagScope scope,
            String query,
            int topK,
            Integer tokenBudget,
            Long testRunId) {
        long started = System.nanoTime();
        String boundedQuery = query == null ? "" : query.substring(0, Math.min(query.length(), 20_000));
        int safeTopK = Math.max(1, Math.min(topK, 20));
        int budget = Math.max(256, Math.min(tokenBudget == null ? defaultTokenBudget : tokenBudget, 20_000));
        int candidateLimit = Math.max(20, safeTopK * 4);
        float[] queryVector = embeddings.embed(boundedQuery);
        List<ScopedChunkSearchService.ScoredChunk> dense = candidatesAcrossAllowedScopes(
                scope, candidateLimit, candidateScope -> search.dense(candidateScope, queryVector, candidateLimit));
        List<ScopedChunkSearchService.ScoredChunk> lexical = candidatesAcrossAllowedScopes(
                scope, candidateLimit, candidateScope -> search.lexical(candidateScope, boundedQuery, candidateLimit));

        Map<Long, RankedCandidate> candidates = new HashMap<>();
        addRanked(candidates, dense, true);
        addRanked(candidates, lexical, false);
        Set<String> queryTerms = ScopedChunkSearchService.terms(boundedQuery);
        double maxLexical = lexical.stream().mapToDouble(ScopedChunkSearchService.ScoredChunk::score).max().orElse(1);

        List<RankedCandidate> ranked = candidates.values().stream()
                .peek(candidate -> candidate.rerank(queryTerms, maxLexical))
                .filter(candidate -> candidate.rerankScore >= relevanceThreshold)
                .sorted(Comparator.comparingDouble(RankedCandidate::rerankScore).reversed()
                        .thenComparing(candidate -> candidate.chunk.getChunkKey()))
                .toList();

        List<RagQueryResult> packed = new ArrayList<>();
        List<RagCitation> citations = new ArrayList<>();
        Set<String> contentHashes = new HashSet<>();
        int usedTokens = 0;
        for (RankedCandidate candidate : ranked) {
            if (packed.size() >= safeTopK || !contentHashes.add(candidate.chunk.getContentHash())) continue;
            if (usedTokens + candidate.chunk.getTokenCount() > budget) continue;
            String uri = citationUri(candidate.chunk);
            packed.add(candidate.toResult(uri));
            citations.add(new RagCitation(
                    candidate.chunk.getId(), candidate.chunk.getChunkKey(), uri, candidate.chunk.getSource(),
                    candidate.chunk.getSymbol(), candidate.chunk.getStartLine(), candidate.chunk.getEndLine(),
                    candidate.chunk.getContentHash(), candidate.rerankScore));
            usedTokens += candidate.chunk.getTokenCount();
        }

        String context = buildContext(packed);
        String queryHash = RagHashing.sha256(String.join("\u0000",
                scope.tenantId().toString(), scope.projectId().toString(), scope.commitSha(), boundedQuery,
                embeddings.modelId(), Integer.toString(safeTopK), Integer.toString(budget)));
        String config = "hybrid-rrf-v1;threshold=" + relevanceThreshold + ";topK=" + safeTopK
                + ";budget=" + budget + ";embedding=" + embeddings.modelId();
        int queryTokens = boundedQuery.isBlank() ? 0 : Math.max(1, (boundedQuery.length() + 3) / 4);
        long latencyMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        double embeddingCost = Math.round(
                queryTokens * embeddingCostPerMillionTokens / 1_000_000.0 * 100_000_000.0) / 100_000_000.0;
        RagRetrievalTrace trace = traces.save(new RagRetrievalTrace(
                testRunId, scope.tenantId(), scope.projectId(), scope.commitSha(), queryHash,
                boundedQuery, context, usedTokens, queryTokens, latencyMs, dense.size(), lexical.size(),
                embeddings.modelId(), embeddingCost, config, writeCitations(citations)));
        return new RagRetrievalResult(
                trace.getId(), queryHash, context, usedTokens, List.copyOf(packed), List.copyOf(citations));
    }

    private void addRanked(
            Map<Long, RankedCandidate> candidates,
            List<ScopedChunkSearchService.ScoredChunk> values,
            boolean dense) {
        for (int i = 0; i < values.size(); i++) {
            var scored = values.get(i);
            RankedCandidate candidate = candidates.computeIfAbsent(
                    scored.chunk().getId(), ignored -> new RankedCandidate(scored.chunk()));
            candidate.fusionScore += 1.0 / (RRF_K + i + 1);
            if (dense) {
                candidate.denseScore = scored.score();
                candidate.hasDenseScore = true;
            } else {
                candidate.lexicalScore = scored.score();
                candidate.hasLexicalScore = true;
            }
        }
    }

    private String buildContext(List<RagQueryResult> results) {
        StringBuilder context = new StringBuilder();
        for (RagQueryResult result : results) {
            context.append("[").append(result.citationUri()).append("]\n")
                    .append(result.content()).append("\n\n");
        }
        return context.toString().trim();
    }

    private String citationUri(DocumentChunk chunk) {
        return "rag://tenant/" + chunk.getTenantId()
                + "/project/" + chunk.getProjectId()
                + "/commit/" + chunk.getCommitSha()
                + "/source/" + URLEncoder.encode(chunk.getSource(), StandardCharsets.UTF_8)
                + "?chunk=" + chunk.getChunkKey()
                + "&lines=" + chunk.getStartLine() + "-" + chunk.getEndLine();
    }

    private List<ScopedChunkSearchService.ScoredChunk> candidatesAcrossAllowedScopes(
            RagScope scope,
            int limit,
            java.util.function.Function<RagScope, List<ScopedChunkSearchService.ScoredChunk>> finder) {
        List<ScopedChunkSearchService.ScoredChunk> candidates = new ArrayList<>(finder.apply(scope));
        if (scope.projectId() != 0L) candidates.addAll(finder.apply(RagScope.global()));
        return candidates.stream()
                .sorted(Comparator.comparingDouble(ScopedChunkSearchService.ScoredChunk::score).reversed()
                        .thenComparing(item -> item.chunk().getChunkKey()))
                .limit(limit)
                .toList();
    }

    private String writeCitations(List<RagCitation> citations) {
        try {
            return objectMapper.writeValueAsString(citations);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to persist RAG citations", e);
        }
    }

    private static final class RankedCandidate {
        private final DocumentChunk chunk;
        private double denseScore;
        private double lexicalScore;
        private double fusionScore;
        private double rerankScore;
        private boolean hasDenseScore;
        private boolean hasLexicalScore;

        private RankedCandidate(DocumentChunk chunk) { this.chunk = chunk; }

        private void rerank(Set<String> queryTerms, double maxLexical) {
            double dense = hasDenseScore ? Math.max(0, Math.min(1, (denseScore + 1) / 2)) : 0;
            double lexical = hasLexicalScore && maxLexical > 0 ? lexicalScore / maxLexical : 0;
            double symbolOverlap = ScopedChunkSearchService.lexicalScore(
                    queryTerms,
                    ScopedChunkSearchService.terms((chunk.getSymbol() == null ? "" : chunk.getSymbol())
                            + " " + chunk.getSource()));
            rerankScore = 0.50 * dense + 0.25 * lexical + 0.15 * symbolOverlap
                    + 0.10 * Math.min(1, fusionScore * 30);
        }

        private double rerankScore() { return rerankScore; }

        private RagQueryResult toResult(String uri) {
            return new RagQueryResult(
                    chunk.getId(), chunk.getDocumentId(), chunk.getContent(), denseScore, lexicalScore,
                    fusionScore, rerankScore, chunk.getSource(), chunk.getSymbol(), uri, chunk.getTokenCount());
        }
    }
}

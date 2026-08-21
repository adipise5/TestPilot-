package com.testpilot.rag.service;

import com.testpilot.rag.dto.RagQueryResult;
import com.testpilot.rag.entity.DocumentChunk;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class VectorSearchService {

    public double calculateCosineSimilarity(float[] vectorA, float[] vectorB) {
        if (vectorA == null || vectorB == null || vectorA.length == 0 || vectorB.length == 0) {
            return 0.0;
        }

        int minLength = Math.min(vectorA.length, vectorB.length);
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < minLength; i++) {
            dotProduct += vectorA[i] * vectorB[i];
            normA += vectorA[i] * vectorA[i];
            normB += vectorB[i] * vectorB[i];
        }

        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    public List<RagQueryResult> searchTopK(float[] queryVector, List<DocumentChunk> chunks, int topK) {
        return chunks.stream()
                .map(chunk -> {
                    double score = calculateCosineSimilarity(queryVector, chunk.getEmbeddingVector());
                    return new RagQueryResult(chunk.getId(), chunk.getDocumentId(), chunk.getContent(), score);
                })
                .sorted(Comparator.comparingDouble(RagQueryResult::similarityScore).reversed())
                .limit(topK)
                .toList();
    }
}

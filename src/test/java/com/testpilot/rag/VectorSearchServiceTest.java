package com.testpilot.rag;

import com.testpilot.rag.dto.RagQueryResult;
import com.testpilot.rag.entity.DocumentChunk;
import com.testpilot.rag.service.VectorSearchService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VectorSearchServiceTest {

    @Test
    void shouldCalculateCosineSimilarityCorrectly() {
        VectorSearchService service = new VectorSearchService();

        float[] v1 = new float[]{1.0f, 0.0f, 0.0f};
        float[] v2 = new float[]{1.0f, 0.0f, 0.0f};
        float[] v3 = new float[]{0.0f, 1.0f, 0.0f};

        assertEquals(1.0, service.calculateCosineSimilarity(v1, v2), 0.0001);
        assertEquals(0.0, service.calculateCosineSimilarity(v1, v3), 0.0001);
    }

    @Test
    void shouldSortTopKBySimilarity() {
        VectorSearchService service = new VectorSearchService();

        float[] query = new float[]{1.0f, 0.0f};

        DocumentChunk chunk1 = new DocumentChunk(1L, "Content 1", new float[]{1.0f, 0.0f}); // score = 1.0
        DocumentChunk chunk2 = new DocumentChunk(1L, "Content 2", new float[]{0.707f, 0.707f}); // score ~ 0.707
        DocumentChunk chunk3 = new DocumentChunk(1L, "Content 3", new float[]{0.0f, 1.0f}); // score = 0.0

        List<RagQueryResult> results = service.searchTopK(query, List.of(chunk1, chunk2, chunk3), 2);

        assertEquals(2, results.size());
        assertEquals("Content 1", results.get(0).content());
        assertEquals("Content 2", results.get(1).content());
        assertTrue(results.get(0).similarityScore() > results.get(1).similarityScore());
    }
}

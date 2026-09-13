package com.testpilot.rag;

import com.testpilot.rag.embedding.MockEmbeddingProvider;
import com.testpilot.rag.service.VectorSearchService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockEmbeddingProviderTest {

    @Test
    void honorsConfiguredDimensionsAndPreservesTokenOverlap() {
        MockEmbeddingProvider provider = new MockEmbeddingProvider(64);
        VectorSearchService similarity = new VectorSearchService();

        float[] query = provider.embed("invoice ledger calculation");
        float[] relevant = provider.embed("calculate invoice from ledger");
        float[] unrelated = provider.embed("socket network transport");

        assertEquals(64, query.length);
        assertEquals("mock-token-hash-v1:64", provider.modelId());
        assertTrue(similarity.calculateCosineSimilarity(query, relevant)
                > similarity.calculateCosineSimilarity(query, unrelated));
    }
}

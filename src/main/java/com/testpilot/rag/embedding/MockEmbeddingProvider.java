package com.testpilot.rag.embedding;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;

@Component
@ConditionalOnProperty(name = "ai.embedding-provider", havingValue = "mock", matchIfMissing = true)
public class MockEmbeddingProvider implements EmbeddingProvider {

    private final int dimensions;

    public MockEmbeddingProvider(@Value("${ai.embedding-dimensions:1536}") int dimensions) {
        if (dimensions < 1 || dimensions > 2_000) {
            throw new IllegalArgumentException("Mock embedding dimensions must be between 1 and 2000");
        }
        this.dimensions = dimensions;
    }

    @Override
    public float[] embed(String text) {
        float[] embedding = new float[dimensions];
        if (text == null || text.isBlank()) return embedding;

        Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9_$]+"))
                .filter(term -> term.length() > 1)
                .forEach(term -> {
                    int hash = term.hashCode();
                    int index = Math.floorMod(hash, dimensions);
                    embedding[index] += (hash & 1) == 0 ? 1.0f : -1.0f;
                });

        double norm = 0;
        for (float value : embedding) norm += value * value;
        if (norm > 0) {
            float scale = (float) (1.0 / Math.sqrt(norm));
            for (int i = 0; i < embedding.length; i++) embedding[i] *= scale;
        }
        return embedding;
    }

    @Override
    public String modelId() {
        return "mock-token-hash-v1:" + dimensions;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }
}

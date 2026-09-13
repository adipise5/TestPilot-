package com.testpilot.rag.embedding;

public interface EmbeddingProvider {
    float[] embed(String text);
    String modelId();
    int dimensions();
}

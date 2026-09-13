package com.testpilot.rag.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.common.exception.ExternalServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "ai.embedding-provider", havingValue = "openai")
public class OpenAiEmbeddingProvider implements EmbeddingProvider {

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int dimensions;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiEmbeddingProvider(
            @Value("${ai.embedding-api-key:${AI_API_KEY:}}") String apiKey,
            @Value("${ai.embedding-base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${ai.embedding-model:text-embedding-3-small}") String model,
            @Value("${ai.embedding-dimensions:1536}") int dimensions,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.dimensions = dimensions;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("AI_API_KEY is required for the OpenAI embedding provider");
        }
        if (dimensions < 1 || dimensions > 2_000) {
            throw new IllegalArgumentException("OpenAI embedding dimensions must be between 1 and 2000");
        }
    }

    @Override
    public float[] embed(String text) {
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "model", model,
                    "input", text,
                    "dimensions", dimensions));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/embeddings"))
                    .timeout(Duration.ofSeconds(45))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ExternalServiceException("Embedding provider returned HTTP " + response.statusCode());
            }
            JsonNode values = objectMapper.readTree(response.body()).path("data").path(0).path("embedding");
            if (!values.isArray() || values.size() != dimensions) {
                throw new ExternalServiceException("Embedding provider returned an unexpected vector dimension");
            }
            float[] vector = new float[dimensions];
            for (int i = 0; i < dimensions; i++) {
                vector[i] = (float) values.get(i).asDouble();
                if (!Float.isFinite(vector[i])) {
                    throw new ExternalServiceException("Embedding provider returned a non-finite vector value");
                }
            }
            return vector;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExternalServiceException("Embedding request was interrupted");
        } catch (ExternalServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ExternalServiceException("Embedding generation failed: " + e.getMessage());
        }
    }

    @Override
    public String modelId() {
        return model + ":" + dimensions;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }
}

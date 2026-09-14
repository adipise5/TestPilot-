package com.testpilot.observability.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.ai.client.LlmClient;
import com.testpilot.observability.entity.ModelInvocationTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
public class ModelInvocationRecorder {

    private static final Logger log = LoggerFactory.getLogger(ModelInvocationRecorder.class);

    private final ModelInvocationPersistenceService persistence;
    private final ObjectMapper objectMapper;
    private final String provider;
    private final String model;
    private final double inputCostPerMillion;
    private final double outputCostPerMillion;

    public ModelInvocationRecorder(
            ModelInvocationPersistenceService persistence,
            ObjectMapper objectMapper,
            LlmClient llmClient,
            @Value("${testpilot.observability.llm-input-cost-per-million:0}") double inputCostPerMillion,
            @Value("${testpilot.observability.llm-output-cost-per-million:0}") double outputCostPerMillion) {
        this.persistence = persistence;
        this.objectMapper = objectMapper;
        this.provider = llmClient.providerId();
        this.model = llmClient.modelId();
        this.inputCostPerMillion = Math.max(0, inputCostPerMillion);
        this.outputCostPerMillion = Math.max(0, outputCostPerMillion);
    }

    public <T> T observe(Long testRunId, String operation, String inputMaterial, Supplier<T> invocation) {
        int inputTokens = estimateTokens(inputMaterial);
        long started = System.nanoTime();
        try {
            T result = invocation.get();
            tryPersist(testRunId, operation, inputTokens, estimateTokens(writeValue(result)), started, true, null);
            return result;
        } catch (RuntimeException error) {
            tryPersist(testRunId, operation, inputTokens, 0, started, false, error.getClass().getSimpleName());
            throw error;
        }
    }

    private void tryPersist(
            Long testRunId,
            String operation,
            int inputTokens,
            int outputTokens,
            long started,
            boolean successful,
            String errorType) {
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        double cost = inputTokens * inputCostPerMillion / 1_000_000.0
                + outputTokens * outputCostPerMillion / 1_000_000.0;
        try {
            persistence.save(new ModelInvocationTrace(
                    testRunId, operation, provider, model, inputTokens, outputTokens,
                    Math.round(cost * 100_000_000.0) / 100_000_000.0,
                    latencyMs, successful, errorType));
        } catch (RuntimeException persistenceError) {
            log.warn("Could not persist model invocation telemetry for test run {} operation {}",
                    testRunId, operation, persistenceError);
        }
    }

    private int estimateTokens(String value) {
        return value == null || value.isBlank() ? 0 : Math.max(1, (value.length() + 3) / 4);
    }

    private String writeValue(Object value) {
        try {
            return value == null ? "" : objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "";
        }
    }
}

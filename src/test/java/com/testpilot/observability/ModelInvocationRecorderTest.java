package com.testpilot.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.ai.client.LlmClient;
import com.testpilot.observability.entity.ModelInvocationTrace;
import com.testpilot.observability.service.ModelInvocationPersistenceService;
import com.testpilot.observability.service.ModelInvocationRecorder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class ModelInvocationRecorderTest {

    @Test
    void telemetryFailureDoesNotFailASuccessfulModelCall() {
        ModelInvocationRecorder recorder = recorderWithFailingPersistence();

        String result = recorder.observe(12L, "test-generation-unit", "input", () -> "generated");

        assertEquals("generated", result);
    }

    @Test
    void telemetryFailureDoesNotMaskTheOriginalModelError() {
        ModelInvocationRecorder recorder = recorderWithFailingPersistence();
        IllegalStateException modelError = new IllegalStateException("provider unavailable");

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> recorder.observe(12L, "test-generation-unit", "input", () -> {
                    throw modelError;
                }));

        assertSame(modelError, thrown);
    }

    private ModelInvocationRecorder recorderWithFailingPersistence() {
        ModelInvocationPersistenceService persistence = mock(ModelInvocationPersistenceService.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(persistence).save(any(ModelInvocationTrace.class));
        LlmClient llm = mock(LlmClient.class);
        return new ModelInvocationRecorder(persistence, new ObjectMapper(), llm, 0, 0);
    }
}

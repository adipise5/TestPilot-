package com.testpilot.ai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenAiGeminiLlmClientTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void repairsOnlyUnknownJsonStringEscapesWithoutChangingValidCodeEscapes() throws Exception {
        String invalid = "{\"code\":\"a" + "\\" + "0b\"}";
        String repaired = OpenAiGeminiLlmClient.escapeInvalidJsonBackslashes(invalid);
        assertEquals("a\\0b", mapper.readTree(repaired).path("code").asText());

        String valid = mapper.writeValueAsString(java.util.Map.of("code", "a\\0b"));
        assertEquals(valid, OpenAiGeminiLlmClient.escapeInvalidJsonBackslashes(valid));
    }
}

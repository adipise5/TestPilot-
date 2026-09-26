package com.testpilot.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@ConditionalOnProperty(name = "ai.provider", havingValue = "openai")
public class OpenAiGeminiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiGeminiLlmClient.class);
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiGeminiLlmClient(
            @Value("${ai.api-key:${AI_API_KEY:}}") String apiKey,
            @Value("${ai.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${ai.model:gpt-3.5-turbo}") String model,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String generate(String prompt, String systemInstruction) {
        try {
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "messages", List.of(
                            Map.of("role", "system", "content", systemInstruction),
                            Map.of("role", "user", "content", prompt)
                    ),
                    "temperature", 0.2
            );

            String jsonPayload = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(90))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            var pending = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> response;
            try {
                response = pending.get(90, TimeUnit.SECONDS);
            } catch (TimeoutException timeout) {
                pending.cancel(true);
                throw new IllegalStateException("LLM provider did not respond within 90 seconds", timeout);
            }

            if (response.statusCode() != 200) {
                throw new RuntimeException("LLM API call failed with status: " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            return root.path("choices").get(0).path("message").path("content").asText();

        } catch (Exception e) {
            log.error("Error communicating with LLM API", e);
            throw new RuntimeException("LLM API execution failure: " + e.getMessage(), e);
        }
    }

    @Override
    public <T> T generateStructured(String prompt, String systemInstruction, Class<T> responseType) {
        String jsonPrompt = prompt + "\n\nCRITICAL REQUIREMENT: Return ONLY a valid JSON object matching the requested schema. "
                + "Escape every backslash inside JSON strings, including backslashes in Java source code. "
                + "Do not include markdown code blocks.";
        String rawResponse = generate(jsonPrompt, systemInstruction);

        String cleanedJson = rawResponse.trim();
        if (cleanedJson.startsWith("```json")) {
            cleanedJson = cleanedJson.substring(7);
        }
        if (cleanedJson.startsWith("```")) {
            cleanedJson = cleanedJson.substring(3);
        }
        if (cleanedJson.endsWith("```")) {
            cleanedJson = cleanedJson.substring(0, cleanedJson.length() - 3);
        }
        cleanedJson = cleanedJson.trim();

        try {
            return objectMapper.readValue(cleanedJson, responseType);
        } catch (Exception e) {
            try {
                return objectMapper.readValue(escapeInvalidJsonBackslashes(cleanedJson), responseType);
            } catch (Exception repairedFailure) {
                log.warn("Failed to parse structured LLM response: {}", repairedFailure.getClass().getSimpleName());
                throw new RuntimeException("Malformed LLM JSON output", repairedFailure);
            }
        }
    }

    /** Preserve literal Java/Python escapes such as \0 when a model forgets JSON's second slash. */
    static String escapeInvalidJsonBackslashes(String json) {
        StringBuilder fixed = new StringBuilder(json.length());
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '"' && (i == 0 || !isEscaped(json, i))) inString = !inString;
            if (inString && ch == '\\' && !isEscaped(json, i) && i + 1 < json.length()
                    && "\"\\/bfnrtu".indexOf(json.charAt(i + 1)) < 0) fixed.append('\\');
            fixed.append(ch);
        }
        return fixed.toString();
    }

    private static boolean isEscaped(String text, int quoteIndex) {
        int slashes = 0;
        for (int i = quoteIndex - 1; i >= 0 && text.charAt(i) == '\\'; i--) slashes++;
        return slashes % 2 != 0;
    }

    @Override
    public String providerId() {
        return "openai-compatible";
    }

    @Override
    public String modelId() {
        return model;
    }

}

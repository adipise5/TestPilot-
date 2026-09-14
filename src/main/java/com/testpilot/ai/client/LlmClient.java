package com.testpilot.ai.client;

public interface LlmClient {
    String generate(String prompt, String systemInstruction);
    <T> T generateStructured(String prompt, String systemInstruction, Class<T> responseType);

    default String providerId() {
        return "unspecified";
    }

    default String modelId() {
        return "unspecified";
    }
}

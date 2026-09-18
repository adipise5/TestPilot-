package com.testpilot.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.testpilot.ai.client.OpenAiGeminiLlmClient;
import com.testpilot.testing.generation.SourceInput;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ReviewProviderContractTest {
    @Test void realClientParsesProviderProtocolAndAgentValidatesReturnedEvidence() throws Exception {
        var mapper = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var captured = new AtomicReference<String>();
        server.createContext("/v1/chat/completions", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String content = mapper.writeValueAsString(new ReviewReport.BatchResponse(List.of("app.py"), List.of(
                    new ReviewReport.Finding("GOOD_PRACTICE", "MAINTAINABILITY", "INFO", "Explicit return value", "Function exposes a direct deterministic result",
                            "Keep this function side-effect free as it grows; test its output contract", List.of(new ReviewReport.Evidence("app.py", 1, 1, "def value(): return 1"))))));
            byte[] response = mapper.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", Map.of("content", content)))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            var client = new OpenAiGeminiLlmClient("fixture-only-key", "http://127.0.0.1:"+server.getAddress().getPort()+"/v1", "fixture-model", mapper);
            var result = new ReviewAgent(client, mapper).review(List.of(new SourceInput("app.py", "def value(): return 1\n")));
            assertEquals(1, result.findings().size()); assertEquals(0, result.rejected());
            var request = mapper.readTree(captured.get());
            assertEquals("fixture-model", request.path("model").asText());
            assertTrue(request.path("messages").get(0).path("content").asText().contains("GOOD_PRACTICE"));
            assertTrue(request.path("messages").get(1).path("content").asText().contains("app.py"));
        } finally { server.stop(0); }
    }
}

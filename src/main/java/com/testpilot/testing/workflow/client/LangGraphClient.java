package com.testpilot.testing.workflow.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.common.exception.ExternalServiceException;
import com.testpilot.common.exception.InvalidRequestException;
import com.testpilot.testing.workflow.dto.*;
import com.testpilot.testing.workflow.entity.WorkflowRun;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class LangGraphClient {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String internalToken;

    public LangGraphClient(
            ObjectMapper objectMapper,
            @Value("${testpilot.workflow.orchestrator-base-url:http://localhost:8090}") String baseUrl,
            @Value("${testpilot.workflow.internal-token:}") String internalToken) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.internalToken = internalToken;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public WorkflowInvocationResponse start(WorkflowRun workflow) {
        WorkflowInvocationRequest request = new WorkflowInvocationRequest(
                1,
                workflow.getTestRunId(),
                workflow.getProjectId(),
                workflow.getThreadId(),
                workflow.getGraphVersion());
        return post("/v1/workflows", request);
    }

    public WorkflowInvocationResponse resume(WorkflowRun workflow, boolean approved, String comment) {
        return post(
                "/v1/workflows/" + workflow.getThreadId() + "/resume",
                new WorkflowResumeRequest(1, approved, comment));
    }

    private WorkflowInvocationResponse post(String path, Object body) {
        if (internalToken.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new InvalidRequestException(
                    "LangGraph requires WORKFLOW_INTERNAL_TOKEN with at least 32 UTF-8 bytes");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(Duration.ofMinutes(4))
                    .header("Content-Type", "application/json")
                    .header("X-TestPilot-Internal-Token", internalToken)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new ExternalServiceException(
                        "LangGraph invocation failed with status " + response.statusCode());
            }
            return objectMapper.readValue(response.body(), WorkflowInvocationResponse.class);
        } catch (ExternalServiceException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExternalServiceException("LangGraph invocation was interrupted", e);
        } catch (Exception e) {
            throw new ExternalServiceException("LangGraph orchestration service is unavailable", e);
        }
    }
}

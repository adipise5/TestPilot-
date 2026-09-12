package com.testpilot.testing.workflow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.testpilot.common.exception.InvalidRequestException;
import com.testpilot.testing.workflow.dto.WorkflowToolRequest;
import com.testpilot.testing.workflow.dto.WorkflowToolResponse;
import com.testpilot.testing.workflow.entity.WorkflowRun;
import com.testpilot.testing.workflow.service.WorkflowStepPersistenceService.BeginStepResult;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

@Service
public class WorkflowToolService {

    private static final int MAX_STATE_BYTES = 768 * 1024;
    private static final int MAX_OUTPUT_BYTES = 512 * 1024;
    private static final Set<String> SUPPORTED_NODES = Set.of(
            "intake",
            "codebase_mapper",
            "test_planner",
            "unit_test_specialist",
            "module_test_specialist",
            "integration_test_specialist",
            "test_reviewer",
            "execution_coordinator",
            "failure_triage",
            "report");

    private final ObjectMapper objectMapper;
    private final ObjectMapper canonicalMapper;
    private final WorkflowRunService workflowRunService;
    private final WorkflowStepPersistenceService persistenceService;
    private final WorkflowToolOperations operations;

    public WorkflowToolService(
            ObjectMapper objectMapper,
            WorkflowRunService workflowRunService,
            WorkflowStepPersistenceService persistenceService,
            WorkflowToolOperations operations) {
        this.objectMapper = objectMapper;
        this.canonicalMapper = objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.workflowRunService = workflowRunService;
        this.persistenceService = persistenceService;
        this.operations = operations;
    }

    public WorkflowToolResponse invoke(String node, WorkflowToolRequest request) {
        if (!SUPPORTED_NODES.contains(node)) {
            throw new InvalidRequestException("Unsupported workflow tool node: " + node);
        }
        byte[] canonicalState = canonicalJson(request.state());
        if (canonicalState.length > MAX_STATE_BYTES) {
            throw new InvalidRequestException("Workflow state exceeds the 768 KiB tool boundary");
        }

        WorkflowRun workflow = workflowRunService.requireToolContext(
                request.testRunId(), request.threadId(), request.graphVersion());
        String inputHash = sha256(canonicalState);
        BeginStepResult begin = persistenceService.begin(
                workflow.getId(), node, request.idempotencyKey(), inputHash);
        if (begin.replayed()) {
            return new WorkflowToolResponse(
                    1,
                    node,
                    true,
                    parseJson(begin.step().getOutputJson()));
        }

        workflowRunService.markNodeRunning(workflow.getId(), operations.statusFor(node));
        try {
            JsonNode updates = operations.execute(node, workflow, request.state());
            byte[] output = canonicalJson(updates);
            if (output.length > MAX_OUTPUT_BYTES) {
                throw new InvalidRequestException("Workflow tool output exceeds the 512 KiB boundary");
            }
            String outputJson = new String(output, StandardCharsets.UTF_8);
            persistenceService.complete(begin.step().getId(), outputJson);
            return new WorkflowToolResponse(1, node, false, updates);
        } catch (RuntimeException e) {
            persistenceService.fail(begin.step().getId(), safeError(e));
            throw e;
        }
    }

    private byte[] canonicalJson(JsonNode value) {
        try {
            return canonicalMapper.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new InvalidRequestException("Workflow state is not valid JSON");
        }
    }

    private JsonNode parseJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception e) {
            throw new IllegalStateException("Stored workflow tool output is invalid", e);
        }
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String safeError(RuntimeException exception) {
        String message = exception instanceof InvalidRequestException
                ? exception.getMessage()
                : "Workflow tool failed; inspect server logs using the test run identifier";
        return message.substring(0, Math.min(500, message.length()));
    }
}

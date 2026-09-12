package com.testpilot.testing.workflow.controller;

import com.testpilot.testing.workflow.dto.WorkflowToolRequest;
import com.testpilot.testing.workflow.dto.WorkflowToolResponse;
import com.testpilot.testing.workflow.service.InternalWorkflowTokenVerifier;
import com.testpilot.testing.workflow.service.WorkflowToolService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/internal/v1/workflow-tools")
public class InternalWorkflowToolController {

    private final InternalWorkflowTokenVerifier tokenVerifier;
    private final WorkflowToolService workflowToolService;

    public InternalWorkflowToolController(
            InternalWorkflowTokenVerifier tokenVerifier,
            WorkflowToolService workflowToolService) {
        this.tokenVerifier = tokenVerifier;
        this.workflowToolService = workflowToolService;
    }

    @PostMapping("/{node}")
    public ResponseEntity<WorkflowToolResponse> invoke(
            @PathVariable String node,
            @RequestHeader(name = "X-TestPilot-Internal-Token", required = false) String token,
            @Valid @RequestBody WorkflowToolRequest request) {
        tokenVerifier.verify(token);
        return ResponseEntity.ok(workflowToolService.invoke(node, request));
    }
}

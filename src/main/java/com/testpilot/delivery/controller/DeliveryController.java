package com.testpilot.delivery.controller;

import com.testpilot.auth.security.UserPrincipal;
import com.testpilot.delivery.dto.DeliveryDecisionRequest;
import com.testpilot.delivery.dto.DeliveryResponse;
import com.testpilot.delivery.service.DeliveryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/test-runs/{testRunId}/delivery")
public class DeliveryController {

    private final DeliveryService delivery;

    public DeliveryController(DeliveryService delivery) {
        this.delivery = delivery;
    }

    @PostMapping
    public ResponseEntity<DeliveryResponse> createProposal(
            @PathVariable Long testRunId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(delivery.createProposal(testRunId, currentUser));
    }

    @GetMapping
    public ResponseEntity<DeliveryResponse> get(
            @PathVariable Long testRunId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(delivery.get(testRunId, currentUser));
    }

    @PostMapping("/decision")
    public ResponseEntity<DeliveryResponse> decide(
            @PathVariable Long testRunId,
            @Valid @RequestBody DeliveryDecisionRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(delivery.decide(testRunId, request, currentUser));
    }

    @PostMapping("/deliver")
    public ResponseEntity<DeliveryResponse> deliver(
            @PathVariable Long testRunId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(delivery.deliver(testRunId, currentUser));
    }
}

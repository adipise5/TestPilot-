package com.testpilot.health.controller;

import com.testpilot.health.dto.HealthStatusResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    @Value("${spring.application.name:testpilot}")
    private String appName;

    @GetMapping
    public ResponseEntity<HealthStatusResponse> checkHealth() {
        HealthStatusResponse response = new HealthStatusResponse(
                "UP",
                appName,
                "0.0.1-SNAPSHOT",
                LocalDateTime.now()
        );
        return ResponseEntity.ok(response);
    }
}

package com.testpilot.health.dto;

import java.time.LocalDateTime;

public record HealthStatusResponse(
        String status,
        String appName,
        String version,
        LocalDateTime timestamp
) {}

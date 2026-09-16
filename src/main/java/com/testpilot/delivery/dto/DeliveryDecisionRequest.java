package com.testpilot.delivery.dto;

import jakarta.validation.constraints.Size;

public record DeliveryDecisionRequest(
        boolean approved,
        @Size(max = 500) String comment
) {}

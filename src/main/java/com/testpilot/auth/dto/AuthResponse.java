package com.testpilot.auth.dto;

import com.testpilot.auth.entity.Role;

public record AuthResponse(
        String token,
        String type,
        Long userId,
        String name,
        String email,
        Role role
) {
    public AuthResponse(String token, Long userId, String name, String email, Role role) {
        this(token, "Bearer", userId, name, email, role);
    }
}

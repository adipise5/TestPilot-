package com.testpilot.auth;

import com.testpilot.auth.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtTokenProviderTest {

    @Test
    void shouldRejectWeakSigningSecretsInsteadOfPaddingThem() {
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider("too-short", 60_000));
        assertDoesNotThrow(() -> new JwtTokenProvider(
                "a-signing-secret-with-at-least-thirty-two-bytes", 60_000));
    }
}

package com.testpilot.testing.workflow.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class InternalWorkflowTokenVerifier {

    private final byte[] expectedToken;

    public InternalWorkflowTokenVerifier(
            @Value("${testpilot.workflow.internal-token:}") String expectedToken) {
        this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    public void verify(String suppliedToken) {
        byte[] supplied = suppliedToken == null
                ? new byte[0]
                : suppliedToken.getBytes(StandardCharsets.UTF_8);
        if (expectedToken.length < 32 || !MessageDigest.isEqual(expectedToken, supplied)) {
            throw new AccessDeniedException("Internal workflow authentication failed");
        }
    }
}

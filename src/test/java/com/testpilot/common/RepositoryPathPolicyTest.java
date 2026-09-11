package com.testpilot.common;

import com.testpilot.common.exception.InvalidRequestException;
import com.testpilot.common.validation.RepositoryPathPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryPathPolicyTest {

    private final RepositoryPathPolicy policy = new RepositoryPathPolicy();

    @Test
    void shouldAcceptMultiModuleJavaSourcePath() {
        assertEquals(
                "service/src/main/java/com/example/App.java",
                policy.validateSourcePath(
                        "App.java", "service/src/main/java/com/example/App.java"));
    }

    @Test
    void shouldRejectAbsoluteParentAndWindowsPaths() {
        assertAll(
                () -> assertThrows(InvalidRequestException.class,
                        () -> policy.validateRepositoryPath("/etc/passwd")),
                () -> assertThrows(InvalidRequestException.class,
                        () -> policy.validateRepositoryPath("src/main/../secret.java")),
                () -> assertThrows(InvalidRequestException.class,
                        () -> policy.validateRepositoryPath("C:\\temp\\secret.java")),
                () -> assertThrows(InvalidRequestException.class,
                        () -> policy.validateRepositoryPath(".git/config")));
    }

    @Test
    void shouldRecognizeSensitiveAndGeneratedClassInputs() {
        assertTrue(policy.isSensitiveOrExcluded("config/.env.production"));
        assertTrue(policy.isSensitiveOrExcluded("service/target/classes/App.class"));
        assertEquals("com.example.AppTest", policy.validateGeneratedTestClass("com.example.AppTest"));
        assertThrows(InvalidRequestException.class,
                () -> policy.validateGeneratedTestClass("../../AppTest"));
    }
}

package com.testpilot.repository;

import com.testpilot.common.exception.InvalidRequestException;
import com.testpilot.common.validation.RepositoryPathPolicy;
import com.testpilot.repository.connector.RepositoryFileContent;
import com.testpilot.repository.connector.RepositoryTreeEntry;
import com.testpilot.repository.entity.BuildSystem;
import com.testpilot.repository.entity.RepositoryArtifactKind;
import com.testpilot.repository.service.RepositoryCatalogPolicy;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryCatalogPolicyTest {

    private final RepositoryCatalogPolicy policy =
            new RepositoryCatalogPolicy(new RepositoryPathPolicy());

    @Test
    void shouldClassifyOnlySupportedTextInputsAndDetectBuildSystem() {
        RepositoryTreeEntry pom = new RepositoryTreeEntry("pom.xml", "1", 10, "blob");
        RepositoryTreeEntry source = new RepositoryTreeEntry(
                "module/src/main/java/com/example/App.java", "2", 10, "blob");
        RepositoryTreeEntry output = new RepositoryTreeEntry(
                "target/classes/App.class", "3", 10, "blob");

        assertEquals(RepositoryArtifactKind.BUILD_MANIFEST, policy.classify(pom).orElseThrow());
        assertEquals(RepositoryArtifactKind.JAVA_SOURCE, policy.classify(source).orElseThrow());
        assertTrue(policy.classify(output).isEmpty());

        var artifact = policy.decode(
                new RepositoryFileContent("pom.xml", "1", "<project/>".getBytes(StandardCharsets.UTF_8)),
                RepositoryArtifactKind.BUILD_MANIFEST);
        assertEquals(BuildSystem.MAVEN, policy.detectBuildSystem(List.of(artifact)));
    }

    @Test
    void shouldRejectBinaryAndCredentialLikeContent() {
        assertThrows(InvalidRequestException.class, () -> policy.decode(
                new RepositoryFileContent("src/main/java/App.java", "1", new byte[]{0, 1, 2}),
                RepositoryArtifactKind.JAVA_SOURCE));
        assertThrows(InvalidRequestException.class, () -> policy.decode(
                new RepositoryFileContent(
                        "src/main/java/App.java",
                        "1",
                        "// ghp_abcdefghijklmnopqrstuvwxyz123456".getBytes(StandardCharsets.UTF_8)),
                RepositoryArtifactKind.JAVA_SOURCE));
    }
}

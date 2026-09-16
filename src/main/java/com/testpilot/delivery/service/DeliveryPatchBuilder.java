package com.testpilot.delivery.service;

import com.testpilot.common.exception.InvalidRequestException;
import com.testpilot.common.validation.RepositoryPathPolicy;
import com.testpilot.delivery.github.DeliveryChange;
import com.testpilot.testing.entity.GeneratedTest;
import com.testpilot.testing.validation.GeneratedTestPolicyValidator;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

@Component
public class DeliveryPatchBuilder {

    private final RepositoryPathPolicy pathPolicy;
    private final GeneratedTestPolicyValidator testPolicy;

    public DeliveryPatchBuilder(RepositoryPathPolicy pathPolicy, GeneratedTestPolicyValidator testPolicy) {
        this.pathPolicy = pathPolicy;
        this.testPolicy = testPolicy;
    }

    public PreparedPatch prepare(List<GeneratedTest> generatedTests, Set<String> basePaths) {
        if (generatedTests == null || generatedTests.isEmpty()) {
            throw new InvalidRequestException("Delivery requires at least one generated test");
        }
        Set<String> seen = new HashSet<>();
        List<DeliveryChange> changes = new ArrayList<>();
        StringBuilder patch = new StringBuilder();

        generatedTests.stream()
                .sorted(java.util.Comparator.comparing(GeneratedTest::getTestClass))
                .forEach(test -> {
                    String testClass = pathPolicy.validateGeneratedTestClass(test.getTestClass());
                    testPolicy.validate(testClass, test.getTestCode(), test.getTestLevel());
                    String path = pathPolicy.validateRepositoryPath(
                            "src/test/java/" + testClass.replace('.', '/') + ".java");
                    if (!seen.add(path)) {
                        throw new InvalidRequestException("Delivery contains a duplicate test path: " + path);
                    }
                    if (basePaths.contains(path)) {
                        throw new InvalidRequestException(
                                "Delivery will not overwrite an existing repository test: " + path);
                    }
                    String content = normalize(test.getTestCode());
                    changes.add(new DeliveryChange(path, content));
                    appendNewFileDiff(patch, path, content);
                });

        String patchText = patch.toString();
        return new PreparedPatch(List.copyOf(changes), patchText, sha256(patchText));
    }

    private String normalize(String value) {
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        return normalized.endsWith("\n") ? normalized : normalized + "\n";
    }

    private void appendNewFileDiff(StringBuilder patch, String path, String content) {
        String withoutTerminalNewline = content.substring(0, content.length() - 1);
        String[] lines = withoutTerminalNewline.split("\n", -1);
        patch.append("diff --git a/").append(path).append(" b/").append(path).append('\n')
                .append("new file mode 100644\n")
                .append("--- /dev/null\n")
                .append("+++ b/").append(path).append('\n')
                .append("@@ -0,0 +1,").append(lines.length).append(" @@\n");
        for (String line : lines) {
            patch.append('+').append(line).append('\n');
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record PreparedPatch(List<DeliveryChange> changes, String patchText, String patchSha256) {}
}

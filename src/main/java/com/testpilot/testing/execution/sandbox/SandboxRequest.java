package com.testpilot.testing.execution.sandbox;

import com.testpilot.testing.generation.SourceInput;
import java.util.List;

public record SandboxRequest(String language, String framework, String sourcePath,
                             List<TestFile> tests, List<SourceInput> files) {
    public record TestFile(String path, String content) {}
}

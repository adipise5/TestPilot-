package com.testpilot.testing.report;

import com.testpilot.testing.generation.SourceInput;
import com.testpilot.testing.execution.sandbox.SandboxRequest;
import java.util.List;

/** Frozen before execution; later source/catalog changes cannot change this report. */
public record ReportContext(String snapshotId, String commitSha, String language, String framework,
                            SourceInput source, List<SandboxRequest.TestFile> tests) {}

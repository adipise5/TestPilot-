package com.testpilot.testing.execution.sandbox;

import java.util.List;

public record SandboxResult(String outcome, Integer exitCode, String output, List<CaseResult> tests, CoverageEvidence coverage) {
    public SandboxResult {
        if (coverage == null) coverage = CoverageEvidence.unavailable("No coverage evidence recorded");
    }
    public SandboxResult(String outcome, Integer exitCode, String output, List<CaseResult> tests) {
        this(outcome, exitCode, output, tests, CoverageEvidence.unavailable("No coverage evidence recorded"));
    }
    public record CaseResult(String name, String status, String message, double seconds) {}
    public static SandboxResult failure(String outcome, String message) {
        return new SandboxResult(outcome, null, message, List.of());
    }
}

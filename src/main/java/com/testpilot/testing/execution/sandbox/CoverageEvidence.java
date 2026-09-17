package com.testpilot.testing.execution.sandbox;

import java.util.*;

/** Tool-reported lines, normalized against the exact input snapshot before persistence. */
public record CoverageEvidence(String status, String tool, String sourcePath,
                               List<Integer> executedLines, List<Integer> missingLines, String note) {
    public static CoverageEvidence unavailable(String note) {
        return new CoverageEvidence("UNAVAILABLE", null, null, List.of(), List.of(), note);
    }
    public static CoverageEvidence invalid() {
        return new CoverageEvidence("INVALID", null, null, List.of(), List.of(), "Coverage evidence failed snapshot/shape validation; no percentage inferred");
    }
    public Double percent() {
        int total = executedLines.size() + missingLines.size();
        return "MEASURED".equals(status) && total > 0 ? Math.round(10000.0 * executedLines.size() / total) / 100.0 : null;
    }
    public static CoverageEvidence validate(CoverageEvidence value, SandboxRequest request) {
        if (value == null) return unavailable("Worker did not supply coverage evidence");
        if (value.status() == null || value.note() == null || value.note().length() > 2000
                || value.executedLines() == null || value.missingLines() == null) return invalid();
        if (Set.of("UNAVAILABLE", "INVALID").contains(value.status())) {
            return new CoverageEvidence(value.status(), null, null, List.of(), List.of(), value.note());
        }
        if (!Set.of("MEASURED", "NO_EXECUTABLE_LINES").contains(value.status()) || value.tool() == null
                || !Set.of("JaCoCo 0.8.12", "coverage.py 7.10.6", "Jest / V8", "Vitest / V8", "Vitest (proposed) / V8").contains(value.tool())
                || !Objects.equals(request.sourcePath(), value.sourcePath())
                || value.executedLines().size() + value.missingLines().size() > 50000) return invalid();
        String expectedTool = switch (request.language()) {
            case "Java" -> "JaCoCo 0.8.12";
            case "Python" -> "coverage.py 7.10.6";
            default -> request.framework() + " / V8";
        };
        if (!expectedTool.equals(value.tool())) return invalid();
        var source = request.files().stream().filter(f -> f.path().equals(value.sourcePath())).findFirst();
        if (source.isEmpty()) return invalid();
        long lines = source.get().content().lines().count();
        Set<Integer> seen = new HashSet<>();
        for (var group : List.of(value.executedLines(), value.missingLines())) {
            for (Integer number : group) if (number == null || number < 1 || number > lines || !seen.add(number)) return invalid();
        }
        if (seen.isEmpty() != value.status().equals("NO_EXECUTABLE_LINES")) return invalid();
        return new CoverageEvidence(value.status(), value.tool(), value.sourcePath(),
                value.executedLines().stream().sorted().toList(), value.missingLines().stream().sorted().toList(), value.note());
    }
}

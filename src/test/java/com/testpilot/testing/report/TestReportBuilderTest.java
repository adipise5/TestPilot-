package com.testpilot.testing.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.testing.execution.sandbox.*;
import com.testpilot.testing.generation.SourceInput;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TestReportBuilderTest {
    private final TestReportBuilder builder = new TestReportBuilder(new ObjectMapper());
    private final SourceInput source = new SourceInput("app.py", "def divide(a, b):\n    return a / b\n");
    private final ReportContext context = new ReportContext("snapshot", "manual", "Python", "pytest", source,
            List.of(new SandboxRequest.TestFile("tests/test_app.py", "def test_bad():\n    assert True\n")));
    private TestReport report(SandboxResult result) { return builder.build(1L, 2L, context, result, LocalDateTime.now()); }

    @Test void classifiesFailuresWithoutInventingSourceBlameAndPinsLineEvidence() {
        var report = report(new SandboxResult("TEST_FAILURE", 1, "ZeroDivisionError: division by zero", List.of(
                new SandboxResult.CaseResult("divide", "FAILED", "ZeroDivisionError: division by zero", 0.1))));
        assertEquals("UNEXPECTED_EXCEPTION", report.failures().get(0).category());
        assertEquals("TEST_FAILURE", report.summary().classification());
        var division = report.findings().stream().filter(f -> f.title().equals("Review the divisor contract")).findFirst().orElseThrow();
        assertEquals("HYPOTHESIS", division.basis());
        assertEquals(2, division.location().line());
        assertEquals("    return a / b", division.location().snippet());
        assertTrue(report.findings().stream().anyMatch(f -> f.id().startsWith("constant-assertion")));
        assertEquals(TestReportBuilder.hash(source.content()), report.source().sha256());
    }

    @Test void absentCoverageIsNotZeroAndInfrastructureDoesNotImplySourceBug() {
        var report = report(SandboxResult.failure("INFRASTRUCTURE_FAILURE", "Docker unavailable"));
        assertNull(report.coverage().linePercent());
        assertNull(report.coverage().totalLines());
        assertTrue(report.failures().isEmpty());
        assertFalse(report.findings().stream().anyMatch(f -> f.category().equals("SOURCE_IMPROVEMENT")));
    }

    @Test void coverageShowsPartialAndRealZeroWithoutClaimingBranchOrMutationEvidence() {
        for (var executed : List.of(List.of(1), List.<Integer>of())) {
            var coverage = new CoverageEvidence("MEASURED", "coverage.py 7.10.6", "app.py", executed,
                    executed.isEmpty() ? List.of(1, 2) : List.of(2), "fixture");
            var report = report(new SandboxResult("SUCCESS", 0, "", List.of(), coverage));
            assertEquals(executed.isEmpty() ? 0.0 : 50.0, report.coverage().linePercent());
            var finding = report.findings().stream().filter(f -> f.id().equals("uncovered-source")).findFirst().orElseThrow();
            assertEquals(coverage.missingLines().get(0), finding.location().line());
        }
    }

    @Test void validatesCoverageAgainstSnapshotAndRejectsForgedShapes() {
        var request = new SandboxRequest("Python", "pytest", "app.py", context.tests(), List.of(source));
        for (var invalid : List.of(
                new CoverageEvidence("MEASURED", "coverage.py 7.10.6", "other.py", List.of(1), List.of(), ""),
                new CoverageEvidence("MEASURED", "coverage.py 7.10.6", "app.py", List.of(99), List.of(), ""),
                new CoverageEvidence("MEASURED", "coverage.py 7.10.6", "app.py", List.of(1), List.of(1), ""),
                new CoverageEvidence("MEASURED", "coverage.py 7.10.6", "app.py", List.of(), List.of(), ""),
                new CoverageEvidence("MEASURED", "invented", "app.py", List.of(1), List.of(), ""))) {
            assertEquals("INVALID", CoverageEvidence.validate(invalid, request).status());
        }
        var valid = new CoverageEvidence("MEASURED", "coverage.py 7.10.6", "app.py", List.of(2, 1), List.of(), "");
        assertEquals(List.of(1, 2), CoverageEvidence.validate(valid, request).executedLines());
    }
}

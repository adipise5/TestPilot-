package com.testpilot.testing;

import com.testpilot.testing.entity.TestResult;
import com.testpilot.testing.entity.TestResultStatus;
import com.testpilot.testing.parser.SurefireReportParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SurefireReportParserTest {

    @Test
    void shouldParseSurefireXmlReportCorrectly(@TempDir Path tempDir) throws Exception {
        String xmlContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.CalculatorTest" time="0.05" tests="2" errors="0" skipped="0" failures="1">
                    <testcase name="shouldAddNumbers" classname="com.example.CalculatorTest" time="0.01"/>
                    <testcase name="shouldDivideNumbers" classname="com.example.CalculatorTest" time="0.02">
                        <failure message="Expected 5 but got 0" type="org.opentest4j.AssertionFailedError">
                            org.opentest4j.AssertionFailedError: Expected 5 but got 0
                                at com.example.CalculatorTest.shouldDivideNumbers(CalculatorTest.java:15)
                        </failure>
                    </testcase>
                </testsuite>
                """;

        File reportFile = tempDir.resolve("TEST-com.example.CalculatorTest.xml").toFile();
        try (FileWriter writer = new FileWriter(reportFile)) {
            writer.write(xmlContent);
        }

        SurefireReportParser parser = new SurefireReportParser();
        List<TestResult> results = parser.parseReports(1L, tempDir.toFile());

        assertEquals(2, results.size());

        TestResult passResult = results.stream().filter(r -> r.getTestName().contains("shouldAddNumbers")).findFirst().orElseThrow();
        assertEquals(TestResultStatus.PASSED, passResult.getStatus());
        assertNull(passResult.getErrorMessage());

        TestResult failResult = results.stream().filter(r -> r.getTestName().contains("shouldDivideNumbers")).findFirst().orElseThrow();
        assertEquals(TestResultStatus.FAILED, failResult.getStatus());
        assertEquals("Expected 5 but got 0", failResult.getErrorMessage());
        assertTrue(failResult.getStackTrace().contains("AssertionFailedError"));
    }
}

package com.testpilot.testing.parser;

import com.testpilot.testing.entity.TestResult;
import com.testpilot.testing.entity.TestResultStatus;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

@Component
public class SurefireReportParser {

    public List<TestResult> parseReports(Long testRunId, File surefireReportsDir) {
        List<TestResult> results = new ArrayList<>();

        if (!surefireReportsDir.exists() || !surefireReportsDir.isDirectory()) {
            return results;
        }

        File[] reportFiles = surefireReportsDir.listFiles((dir, name) -> name.startsWith("TEST-") && name.endsWith(".xml"));
        if (reportFiles == null) {
            return results;
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Secure XML parser configuration against XXE vulnerabilities
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();

            for (File file : reportFiles) {
                try {
                    Document doc = builder.parse(file);
                    doc.getDocumentElement().normalize();
                    NodeList testcases = doc.getElementsByTagName("testcase");

                    for (int i = 0; i < testcases.getLength(); i++) {
                        Node node = testcases.item(i);
                        if (node.getNodeType() == Node.ELEMENT_NODE) {
                            Element element = (Element) node;
                            TestResult testResult = parseTestCase(testRunId, element);
                            results.add(testResult);
                        }
                    }
                } catch (Exception e) {
                    // Log or handle single report parse error without failing entire run
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize XML parser", e);
        }

        return results;
    }

    private TestResult parseTestCase(Long testRunId, Element testcaseElement) {
        String name = testcaseElement.getAttribute("name");
        String classname = testcaseElement.getAttribute("classname");
        String fullTestName = (classname.isEmpty() ? "" : classname + ".") + name;

        String timeStr = testcaseElement.getAttribute("time");
        Double executionTime = 0.0;
        try {
            if (timeStr != null && !timeStr.isEmpty()) {
                executionTime = Double.parseDouble(timeStr);
            }
        } catch (NumberFormatException ignored) {}

        TestResultStatus status = TestResultStatus.PASSED;
        String errorMessage = null;
        String stackTrace = null;

        NodeList failures = testcaseElement.getElementsByTagName("failure");
        NodeList errors = testcaseElement.getElementsByTagName("error");
        NodeList skipped = testcaseElement.getElementsByTagName("skipped");

        if (failures.getLength() > 0) {
            status = TestResultStatus.FAILED;
            Element failureElem = (Element) failures.item(0);
            errorMessage = failureElem.getAttribute("message");
            stackTrace = failureElem.getTextContent();
        } else if (errors.getLength() > 0) {
            status = TestResultStatus.ERROR;
            Element errorElem = (Element) errors.item(0);
            errorMessage = errorElem.getAttribute("message");
            stackTrace = errorElem.getTextContent();
        } else if (skipped.getLength() > 0) {
            status = TestResultStatus.SKIPPED;
            Element skippedElem = (Element) skipped.item(0);
            errorMessage = skippedElem.getAttribute("message");
        }

        return new TestResult(
                testRunId,
                fullTestName,
                status,
                errorMessage,
                stackTrace,
                executionTime
        );
    }
}

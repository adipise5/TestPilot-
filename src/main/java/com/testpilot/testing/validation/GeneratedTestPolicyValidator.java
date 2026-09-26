package com.testpilot.testing.validation;

import com.testpilot.common.exception.InvalidRequestException;
import com.testpilot.testing.entity.TestLevel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class GeneratedTestPolicyValidator {

    private static final Pattern EMPTY_NO_THROW = Pattern.compile(
            "assertDoesNotThrow\\s*\\(\\s*\\(\\s*\\)\\s*->\\s*\\{\\s*}\\s*\\)");
    private static final Pattern EMPTY_CATCH = Pattern.compile(
            "catch\\s*\\([^)]*\\)\\s*\\{\\s*}");
    private static final Pattern DIRECT_MAIN = Pattern.compile("\\b[A-Za-z_$][\\w$]*\\.main\\s*\\(");

    private static final List<String> FORBIDDEN_EXECUTION_APIS = List.of(
            "Runtime.getRuntime(",
            "new ProcessBuilder(",
            "System.exit(",
            "System.load(",
            "System.loadLibrary(");
    private static final List<String> DIRECT_NETWORK_APIS = List.of(
            "new Socket(",
            "new URL(",
            "HttpClient.newHttpClient(",
            "RestTemplate(",
            "WebClient.create(");

    public List<String> validate(String testClass, String testCode, TestLevel level) {
        if (testCode == null || testCode.isBlank() || testCode.length() > 200_000) {
            throw new InvalidRequestException("Test policy rejected empty or oversized code for " + testClass);
        }
        if (!testCode.contains("@Test")) {
            throw new InvalidRequestException("Test policy requires at least one JUnit @Test for " + testClass);
        }
        String simpleName = testClass.substring(testClass.lastIndexOf('.') + 1);
        if (!testCode.contains("class " + simpleName)) {
            throw new InvalidRequestException("Generated code does not declare expected class: " + simpleName);
        }
        rejectAny(testCode, FORBIDDEN_EXECUTION_APIS, "process/native execution API", testClass);
        rejectAny(testCode, DIRECT_NETWORK_APIS, "uncontrolled network API", testClass);
        String executableCode = testCode.replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//[^\\r\\n]*", "");
        if (EMPTY_NO_THROW.matcher(executableCode).find() || EMPTY_CATCH.matcher(executableCode).find()) {
            throw new InvalidRequestException("Test policy rejected a no-op assertion or swallowed exception in " + testClass);
        }

        List<String> checks = new ArrayList<>();
        checks.add("junit-structure");
        checks.add("no-process-or-native-api");
        checks.add("no-uncontrolled-network-api");

        switch (level) {
            case UNIT -> {
                rejectDirectMain(executableCode, testClass);
                rejectContains(testCode, "@SpringBootTest", "Unit tests must not start a Spring application context", testClass);
                rejectContains(testCode, "@Testcontainers", "Unit tests must not start infrastructure containers", testClass);
                checks.add("unit-isolation");
            }
            case MODULE -> {
                rejectDirectMain(executableCode, testClass);
                rejectContains(testCode, "@SpringBootTest", "Module tests must not start the complete application", testClass);
                rejectContains(testCode, "@Testcontainers", "Module tests must not cross infrastructure boundaries", testClass);
                checks.add("module-boundary");
            }
            case INTEGRATION -> {
                rejectContains(testCode, "@SpringBootTest", "Full Spring application context is unavailable in isolated integration tests", testClass);
                boolean marked = testCode.contains("@Tag(\"integration\")")
                        || testCode.contains("@DataJpaTest")
                        || testCode.contains("@Testcontainers");
                if (!marked) {
                    throw new InvalidRequestException(
                            "Integration tests require an integration tag or a controlled framework fixture: " + testClass);
                }
                checks.add("controlled-integration-boundary");
            }
        }
        return List.copyOf(checks);
    }

    private void rejectAny(String code, List<String> patterns, String reason, String testClass) {
        patterns.stream().filter(code::contains).findFirst().ifPresent(pattern -> {
            throw new InvalidRequestException("Test policy rejected " + reason + " in " + testClass + ": " + pattern);
        });
    }

    private void rejectContains(String code, String pattern, String reason, String testClass) {
        if (code.contains(pattern)) throw new InvalidRequestException(reason + ": " + testClass);
    }

    private void rejectDirectMain(String code, String testClass) {
        if (DIRECT_MAIN.matcher(code).find()) {
            throw new InvalidRequestException("Unit and module tests must not launch an application main method: " + testClass);
        }
    }
}

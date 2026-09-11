package com.testpilot.testing.execution;

import com.testpilot.project.entity.CodeFile;
import com.testpilot.common.exception.InvalidRequestException;
import com.testpilot.common.validation.RepositoryPathPolicy;
import com.testpilot.testing.entity.GeneratedTest;
import com.testpilot.testing.entity.TestResult;
import com.testpilot.testing.parser.SurefireReportParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class TestExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TestExecutionService.class);
    private static final int MAX_CAPTURED_OUTPUT_BYTES = 64 * 1024;
    private static final long EXECUTION_TIMEOUT_SECONDS = 60;
    private final SurefireReportParser surefireReportParser;
    private final RepositoryPathPolicy repositoryPathPolicy;

    public TestExecutionService(
            SurefireReportParser surefireReportParser,
            RepositoryPathPolicy repositoryPathPolicy) {
        this.surefireReportParser = surefireReportParser;
        this.repositoryPathPolicy = repositoryPathPolicy;
    }

    public TestExecutionOutcome executeTests(
            Long testRunId,
            List<CodeFile> sourceFiles,
            List<GeneratedTest> generatedTests) {
        Path workspaceDir = null;

        try {
            Path workspaceRoot = Files.createDirectories(Path.of("target", "workspaces").toAbsolutePath().normalize());
            rejectSymbolicLink(workspaceRoot);
            workspaceDir = Files.createTempDirectory(workspaceRoot, "run-" + testRunId + "-");

            writeWorkspacePomXml(workspaceDir);

            for (CodeFile codeFile : sourceFiles) {
                String path = repositoryPathPolicy.validateSourcePath(
                        codeFile.getFileName(), codeFile.getFilePath());
                writeSourceFile(workspaceDir, path, codeFile.getContent());
            }

            for (GeneratedTest test : generatedTests) {
                repositoryPathPolicy.validateGeneratedTestClass(test.getTestClass());
                String testFilePath = "src/test/java/" + test.getTestClass().replace('.', '/') + ".java";
                writeSourceFile(workspaceDir, testFilePath, test.getTestCode());
            }

            MavenProcessResult processResult = runMavenTestProcess(workspaceDir);
            if (processResult.timedOut()) {
                return new TestExecutionOutcome(
                        TestExecutionOutcomeType.TIMEOUT,
                        List.of(),
                        null,
                        processResult.output());
            }

            File surefireReportsDir = new File(workspaceDir.toFile(), "target/surefire-reports");
            List<TestResult> results = surefireReportParser.parseReports(testRunId, surefireReportsDir);
            TestExecutionOutcomeType outcomeType = classifyOutcome(
                    processResult.exitCode(), processResult.output(), results);
            return new TestExecutionOutcome(outcomeType, results, processResult.exitCode(), processResult.output());

        } catch (InvalidRequestException e) {
            return new TestExecutionOutcome(
                    TestExecutionOutcomeType.INFRASTRUCTURE_FAILURE,
                    List.of(),
                    null,
                    "Workspace input rejected: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new TestExecutionOutcome(
                    TestExecutionOutcomeType.INFRASTRUCTURE_FAILURE,
                    List.of(),
                    null,
                    "Test execution was interrupted");
        } catch (Exception e) {
            log.error("Unable to execute tests for run {}", testRunId, e);
            return new TestExecutionOutcome(
                    TestExecutionOutcomeType.INFRASTRUCTURE_FAILURE,
                    List.of(),
                    null,
                    "Test runner infrastructure failed; inspect server logs using the run identifier");

        } finally {
            if (workspaceDir != null) {
                log.info("Test execution completed for workspace: {}", workspaceDir.toAbsolutePath());
            }
        }
    }

    private void writeWorkspacePomXml(Path workspaceDir) throws IOException {
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.testpilot.runner</groupId>
                    <artifactId>test-runner</artifactId>
                    <version>1.0.0</version>
                    <properties>
                        <maven.compiler.source>17</maven.compiler.source>
                        <maven.compiler.target>17</maven.compiler.target>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter-api</artifactId>
                            <version>5.10.2</version>
                            <scope>test</scope>
                        </dependency>
                        <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter-engine</artifactId>
                            <version>5.10.2</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-compiler-plugin</artifactId>
                                <version>3.11.0</version>
                            </plugin>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-surefire-plugin</artifactId>
                                <version>3.1.2</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """;

        Files.writeString(
                workspaceDir.resolve("pom.xml"),
                pomContent,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
    }

    private void writeSourceFile(Path workspaceDir, String relativePath, String content) throws IOException {
        Path normalizedWorkspace = workspaceDir.toAbsolutePath().normalize();
        Path fullPath = normalizedWorkspace.resolve(relativePath).normalize();
        if (!fullPath.startsWith(normalizedWorkspace)) {
            throw new InvalidRequestException("Repository path escapes the execution workspace");
        }

        Files.createDirectories(fullPath.getParent());
        verifyNoSymbolicLinks(normalizedWorkspace, fullPath.getParent());
        if (Files.exists(fullPath, LinkOption.NOFOLLOW_LINKS)) {
            rejectSymbolicLink(fullPath);
        }
        Files.writeString(
                fullPath,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
    }

    private MavenProcessResult runMavenTestProcess(Path workspaceDir) throws IOException, InterruptedException {
        String mavenCmd = isWindows() ? "mvn.cmd" : "mvn";
        ProcessBuilder processBuilder = new ProcessBuilder(mavenCmd, "clean", "test");
        processBuilder.directory(workspaceDir.toFile());
        processBuilder.redirectErrorStream(true);
        Path outputFile = workspaceDir.resolve("maven-output.log");
        processBuilder.redirectOutput(outputFile.toFile());

        Process process = processBuilder.start();
        boolean completedInTime = process.waitFor(EXECUTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (!completedInTime) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            return new MavenProcessResult(null, true, readBoundedOutput(outputFile));
        }
        return new MavenProcessResult(process.exitValue(), false, readBoundedOutput(outputFile));
    }

    private String readBoundedOutput(Path outputFile) throws IOException {
        try (InputStream input = Files.newInputStream(outputFile)) {
            byte[] bytes = input.readNBytes(MAX_CAPTURED_OUTPUT_BYTES + 1);
            boolean truncated = bytes.length > MAX_CAPTURED_OUTPUT_BYTES;
            int length = truncated ? MAX_CAPTURED_OUTPUT_BYTES : bytes.length;
            String output = new String(bytes, 0, length, StandardCharsets.UTF_8);
            return truncated ? output + "\n[OUTPUT TRUNCATED BY TESTPILOT]" : output;
        }
    }

    private TestExecutionOutcomeType classifyOutcome(
            int exitCode,
            String output,
            List<TestResult> results) {
        boolean testFailures = results.stream().anyMatch(result ->
                result.getStatus() == com.testpilot.testing.entity.TestResultStatus.FAILED
                        || result.getStatus() == com.testpilot.testing.entity.TestResultStatus.ERROR);
        if (testFailures) {
            return TestExecutionOutcomeType.TEST_FAILURE;
        }
        if (exitCode == 0) {
            return TestExecutionOutcomeType.SUCCESS;
        }

        String normalizedOutput = output.toLowerCase();
        if (normalizedOutput.contains("compilation failure")
                || normalizedOutput.contains("compilation error")
                || normalizedOutput.contains("maven-compiler-plugin")) {
            return TestExecutionOutcomeType.COMPILATION_FAILURE;
        }
        return TestExecutionOutcomeType.INFRASTRUCTURE_FAILURE;
    }

    private void verifyNoSymbolicLinks(Path workspace, Path directory) {
        Path current = workspace;
        for (Path part : workspace.relativize(directory)) {
            current = current.resolve(part);
            rejectSymbolicLink(current);
        }
    }

    private void rejectSymbolicLink(Path path) {
        if (Files.isSymbolicLink(path)) {
            throw new InvalidRequestException("Symbolic links are not allowed in execution workspaces");
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private record MavenProcessResult(Integer exitCode, boolean timedOut, String output) {}
}

package com.testpilot.testing.execution;

import com.testpilot.common.exception.InvalidRequestException;
import com.testpilot.common.validation.RepositoryPathPolicy;
import com.testpilot.project.entity.CodeFile;
import com.testpilot.repository.entity.RepositoryArtifact;
import com.testpilot.testing.entity.GeneratedTest;
import com.testpilot.testing.entity.TestResult;
import com.testpilot.testing.parser.SurefireReportParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

@Service
public class TestExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TestExecutionService.class);
    private static final int MAX_CAPTURED_OUTPUT_BYTES = 64 * 1024;
    private final SurefireReportParser surefireReportParser;
    private final RepositoryPathPolicy repositoryPathPolicy;
    private final DockerSandboxCommandFactory dockerCommands;
    private final String backend;
    private final Duration executionTimeout;
    private final boolean mutationEnabled;

    @Autowired
    public TestExecutionService(
            SurefireReportParser surefireReportParser,
            RepositoryPathPolicy repositoryPathPolicy,
            DockerSandboxCommandFactory dockerCommands,
            @Value("${testpilot.execution.backend:container}") String backend,
            @Value("${testpilot.execution.timeout-seconds:60}") long timeoutSeconds,
            @Value("${testpilot.execution.mutation-enabled:false}") boolean mutationEnabled) {
        this.surefireReportParser = surefireReportParser;
        this.repositoryPathPolicy = repositoryPathPolicy;
        this.dockerCommands = dockerCommands;
        this.backend = backend;
        this.executionTimeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.mutationEnabled = mutationEnabled;
    }

    public TestExecutionService(
            SurefireReportParser surefireReportParser,
            RepositoryPathPolicy repositoryPathPolicy) {
        this(
                surefireReportParser,
                repositoryPathPolicy,
                new DockerSandboxCommandFactory("testpilot-worker:local", "bridge", "1g", "1.0", 128),
                "local",
                60,
                false);
    }

    public TestExecutionOutcome executeTests(
            Long testRunId,
            List<CodeFile> sourceFiles,
            List<GeneratedTest> generatedTests) {
        return executeTests(testRunId, sourceFiles, generatedTests, () -> false, () -> {});
    }

    public TestExecutionOutcome executeTests(
            Long testRunId,
            List<CodeFile> sourceFiles,
            List<GeneratedTest> generatedTests,
            BooleanSupplier cancellationRequested,
            Runnable heartbeat) {
        return executeInternal(
                testRunId, sourceFiles, List.of(), generatedTests, cancellationRequested, heartbeat);
    }

    public TestExecutionOutcome executeCatalog(
            Long testRunId,
            List<RepositoryArtifact> catalog,
            List<GeneratedTest> generatedTests,
            BooleanSupplier cancellationRequested,
            Runnable heartbeat) {
        return executeInternal(
                testRunId, List.of(), catalog, generatedTests, cancellationRequested, heartbeat);
    }

    private TestExecutionOutcome executeInternal(
            Long testRunId,
            List<CodeFile> sourceFiles,
            List<RepositoryArtifact> catalog,
            List<GeneratedTest> generatedTests,
            BooleanSupplier cancellationRequested,
            Runnable heartbeat) {
        Path workspaceDir = null;
        Path workspaceRoot = null;

        try {
            workspaceRoot = Files.createDirectories(Path.of("target", "workspaces").toAbsolutePath().normalize());
            rejectSymbolicLink(workspaceRoot);
            workspaceDir = Files.createTempDirectory(workspaceRoot, "run-" + testRunId + "-");
            if (catalog.isEmpty()) {
                writeWorkspacePomXml(workspaceDir);
                for (CodeFile codeFile : sourceFiles) {
                    String path = repositoryPathPolicy.validateSourcePath(
                            codeFile.getFileName(), codeFile.getFilePath());
                    writeWorkspaceFile(workspaceDir, path, codeFile.getContent());
                }
            } else {
                boolean hasRootMavenBuild = catalog.stream().anyMatch(artifact -> artifact.getPath().equals("pom.xml"));
                if (!hasRootMavenBuild) {
                    throw new InvalidRequestException(
                            "The isolated repository runner currently requires a root Maven pom.xml");
                }
                for (RepositoryArtifact artifact : catalog) {
                    String path = repositoryPathPolicy.validateRepositoryPath(artifact.getPath());
                    if (repositoryPathPolicy.isSensitiveOrExcluded(path)) {
                        throw new InvalidRequestException("Catalog contains a prohibited execution path: " + path);
                    }
                    if (!sha256(artifact.getContent()).equals(artifact.getContentHash())) {
                        throw new InvalidRequestException("Catalog content hash no longer matches immutable artifact: " + path);
                    }
                    writeWorkspaceFile(workspaceDir, path, artifact.getContent());
                }
            }

            for (GeneratedTest test : generatedTests) {
                repositoryPathPolicy.validateGeneratedTestClass(test.getTestClass());
                String testFilePath = generatedTestPath(test);
                writeWorkspaceFile(workspaceDir, testFilePath, test.getTestCode());
            }

            if ("container".equalsIgnoreCase(backend)) prepareContainerWorkspace(workspaceDir);

            ProcessResult processResult = "container".equalsIgnoreCase(backend)
                    ? runContainerStages(workspaceDir, cancellationRequested, heartbeat)
                    : runProcess(localMavenCommand(), workspaceDir, workspaceDir.resolve("maven-output.log"),
                            cancellationRequested, heartbeat, executionTimeout);

            if (processResult.cancelled()) {
                return outcome(TestExecutionOutcomeType.CANCELLED, List.of(), null,
                        processResult.output(), TestExecutionMetrics.unavailable());
            }
            if (processResult.timedOut()) {
                return outcome(TestExecutionOutcomeType.TIMEOUT, List.of(), null,
                        processResult.output(), TestExecutionMetrics.unavailable());
            }

            File surefireReportsDir = new File(workspaceDir.toFile(), "target/surefire-reports");
            List<TestResult> results = surefireReportParser.parseReports(testRunId, surefireReportsDir);
            TestExecutionOutcomeType outcomeType = classifyOutcome(
                    processResult.exitCode(), processResult.output(), results);
            return outcome(
                    outcomeType,
                    results,
                    processResult.exitCode(),
                    processResult.output(),
                    collectMetrics(workspaceDir));

        } catch (InvalidRequestException e) {
            return outcome(TestExecutionOutcomeType.INFRASTRUCTURE_FAILURE, List.of(), null,
                    "Workspace input rejected: " + e.getMessage(), TestExecutionMetrics.unavailable());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return outcome(TestExecutionOutcomeType.INFRASTRUCTURE_FAILURE, List.of(), null,
                    "Test execution was interrupted", TestExecutionMetrics.unavailable());
        } catch (Exception e) {
            log.error("Unable to execute tests for run {}", testRunId, e);
            return outcome(TestExecutionOutcomeType.INFRASTRUCTURE_FAILURE, List.of(), null,
                    "Test runner infrastructure failed; inspect server logs using the run identifier",
                    TestExecutionMetrics.unavailable());
        } finally {
            if (workspaceDir != null && workspaceRoot != null) {
                deleteWorkspace(workspaceRoot, workspaceDir);
            }
        }
    }

    private TestExecutionOutcome outcome(
            TestExecutionOutcomeType type,
            List<TestResult> results,
            Integer exitCode,
            String output,
            TestExecutionMetrics metrics) {
        return new TestExecutionOutcome(type, results, exitCode, output, backend.toLowerCase(), metrics);
    }

    private ProcessResult runContainerStages(
            Path workspaceDir,
            BooleanSupplier cancellationRequested,
            Runnable heartbeat) throws IOException, InterruptedException {
        Path cacheRoot = Files.createDirectories(Path.of("target", "dependency-cache").toAbsolutePath().normalize());
        rejectSymbolicLink(cacheRoot);
        Path dependencyCache = Files.createTempDirectory(cacheRoot, "run-");
        try {
            try {
                Files.setPosixFilePermissions(dependencyCache, PosixFilePermissions.fromString("rwxrwxrwx"));
            } catch (UnsupportedOperationException ignored) {
                // Windows and some mounted filesystems do not expose POSIX modes.
            }

            ProcessResult dependencyResult = runProcess(
                    dockerCommands.dependencyResolution(workspaceDir, dependencyCache),
                    workspaceDir,
                    workspaceDir.resolve("dependency-resolution.log"),
                    cancellationRequested,
                    heartbeat,
                    executionTimeout.multipliedBy(2));
            if (dependencyResult.exitCode() == null || dependencyResult.exitCode() != 0) {
                return dependencyResult.withOutput("Dependency stage failed:\n" + dependencyResult.output());
            }
            return runProcess(
                    dockerCommands.testExecution(workspaceDir, dependencyCache, mutationEnabled),
                    workspaceDir,
                    workspaceDir.resolve("maven-output.log"),
                    cancellationRequested,
                    heartbeat,
                    executionTimeout);
        } finally {
            deleteWorkspace(cacheRoot, dependencyCache);
        }
    }

    private ProcessResult runProcess(
            List<String> command,
            Path workspaceDir,
            Path outputFile,
            BooleanSupplier cancellationRequested,
            Runnable heartbeat,
            Duration timeout) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workspaceDir.toFile());
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(outputFile.toFile());

        Process process = processBuilder.start();
        long deadline = System.nanoTime() + timeout.toNanos();
        long nextHeartbeat = System.nanoTime();
        while (process.isAlive()) {
            if (cancellationRequested.getAsBoolean()) {
                terminateProcessTree(process);
                return new ProcessResult(null, false, true, readBoundedOutput(outputFile));
            }
            if (System.nanoTime() >= deadline) {
                terminateProcessTree(process);
                return new ProcessResult(null, true, false, readBoundedOutput(outputFile));
            }
            if (System.nanoTime() >= nextHeartbeat) {
                heartbeat.run();
                nextHeartbeat = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            }
            process.waitFor(250, TimeUnit.MILLISECONDS);
        }
        return new ProcessResult(process.exitValue(), false, false, readBoundedOutput(outputFile));
    }

    private List<String> localMavenCommand() {
        return List.of(isWindows() ? "mvn.cmd" : "mvn", "--batch-mode", "--no-transfer-progress", "clean", "test");
    }

    private void terminateProcessTree(Process process) throws InterruptedException {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
    }

    private void writeWorkspacePomXml(Path workspaceDir) throws IOException {
        String optionalDependencies = "container".equalsIgnoreCase(backend) ? """
                        <dependency>
                            <groupId>org.mockito</groupId>
                            <artifactId>mockito-junit-jupiter</artifactId>
                            <version>5.11.0</version>
                            <scope>test</scope>
                        </dependency>
                """ : "";
        String mutationPlugin = "container".equalsIgnoreCase(backend) && mutationEnabled ? """
                            <plugin>
                                <groupId>org.pitest</groupId>
                                <artifactId>pitest-maven</artifactId>
                                <version>1.25.8</version>
                                <configuration>
                                    <outputFormats><outputFormat>XML</outputFormat></outputFormats>
                                    <threads>1</threads>
                                    <timeoutConst>4000</timeoutConst>
                                </configuration>
                            </plugin>
                """ : "";
        String optionalPlugins = "container".equalsIgnoreCase(backend) ? """
                            <plugin>
                                <groupId>org.jacoco</groupId>
                                <artifactId>jacoco-maven-plugin</artifactId>
                                <version>0.8.12</version>
                                <executions>
                                    <execution><goals><goal>prepare-agent</goal></goals></execution>
                                    <execution><id>report</id><phase>test</phase><goals><goal>report</goal></goals></execution>
                                </executions>
                            </plugin>
                """ + mutationPlugin : "";
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
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
                            <artifactId>junit-jupiter</artifactId>
                            <version>5.10.2</version>
                            <scope>test</scope>
                        </dependency>
                        %s
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
                            %s
                        </plugins>
                    </build>
                </project>
                """.formatted(optionalDependencies, optionalPlugins);

        Files.writeString(
                workspaceDir.resolve("pom.xml"),
                pomContent,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
    }

    private TestExecutionMetrics collectMetrics(Path workspaceDir) {
        Double lineCoverage = readJacocoLineCoverage(workspaceDir.resolve("target/site/jacoco/jacoco.csv"));
        Double mutationScore = readMutationScore(workspaceDir.resolve("target/pit-reports"));
        return new TestExecutionMetrics(
                lineCoverage,
                mutationScore,
                lineCoverage == null ? "UNAVAILABLE" : "COLLECTED",
                mutationScore == null
                        ? mutationEnabled ? "UNAVAILABLE" : "NOT_REQUESTED"
                        : "COLLECTED");
    }

    private Double readJacocoLineCoverage(Path csv) {
        if (!Files.isRegularFile(csv)) return null;
        try {
            List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
            long missed = 0;
            long covered = 0;
            for (int i = 1; i < lines.size(); i++) {
                String[] columns = lines.get(i).split(",", -1);
                if (columns.length > 8) {
                    missed += Long.parseLong(columns[7]);
                    covered += Long.parseLong(columns[8]);
                }
            }
            long total = missed + covered;
            return total == 0 ? null : Math.round((covered * 10_000.0) / total) / 100.0;
        } catch (Exception e) {
            log.warn("Unable to parse JaCoCo report", e);
            return null;
        }
    }

    private Double readMutationScore(Path reportsRoot) {
        if (!Files.isDirectory(reportsRoot)) return null;
        try {
            Path report;
            try (var paths = Files.walk(reportsRoot)) {
                report = paths.filter(path -> path.getFileName().toString().equals("mutations.xml"))
                        .sorted()
                        .findFirst()
                        .orElse(null);
            }
            if (report == null) return null;
            String xml = Files.readString(report, StandardCharsets.UTF_8);
            long total = xml.split("<mutation ", -1).length - 1;
            long killed = xml.split("status='KILLED'", -1).length - 1
                    + xml.split("status=\"KILLED\"", -1).length - 1;
            return total == 0 ? null : Math.round((killed * 10_000.0) / total) / 100.0;
        } catch (Exception e) {
            log.warn("Unable to parse mutation report", e);
            return null;
        }
    }

    private String generatedTestPath(GeneratedTest test) {
        String source = test.getSourceFile();
        int sourceRoot = source == null ? -1 : source.indexOf("/src/main/java/");
        String modulePrefix = sourceRoot > 0 ? source.substring(0, sourceRoot + 1) : "";
        return modulePrefix + "src/test/java/" + test.getTestClass().replace('.', '/') + ".java";
    }

    private void writeWorkspaceFile(Path workspaceDir, String relativePath, String content) throws IOException {
        Path normalizedWorkspace = workspaceDir.toAbsolutePath().normalize();
        Path fullPath = normalizedWorkspace.resolve(relativePath).normalize();
        if (!fullPath.startsWith(normalizedWorkspace)) {
            throw new InvalidRequestException("Repository path escapes the execution workspace");
        }

        Files.createDirectories(fullPath.getParent());
        verifyNoSymbolicLinks(normalizedWorkspace, fullPath.getParent());
        if (Files.exists(fullPath, LinkOption.NOFOLLOW_LINKS)) rejectSymbolicLink(fullPath);
        Files.writeString(fullPath, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
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

    private TestExecutionOutcomeType classifyOutcome(int exitCode, String output, List<TestResult> results) {
        boolean testFailures = results.stream().anyMatch(result ->
                result.getStatus() == com.testpilot.testing.entity.TestResultStatus.FAILED
                        || result.getStatus() == com.testpilot.testing.entity.TestResultStatus.ERROR);
        if (testFailures) return TestExecutionOutcomeType.TEST_FAILURE;
        if (exitCode == 0) return TestExecutionOutcomeType.SUCCESS;

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

    private void deleteWorkspace(Path workspaceRoot, Path workspaceDir) {
        Path normalizedRoot = workspaceRoot.toAbsolutePath().normalize();
        Path normalizedWorkspace = workspaceDir.toAbsolutePath().normalize();
        if (!normalizedWorkspace.startsWith(normalizedRoot) || normalizedWorkspace.equals(normalizedRoot)) {
            log.error("Refusing to clean unsafe workspace path: {}", normalizedWorkspace);
            return;
        }
        try (var paths = Files.walk(normalizedWorkspace)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("Unable to clean workspace path {}", path);
                }
            });
        } catch (IOException e) {
            log.warn("Unable to enumerate workspace for cleanup: {}", normalizedWorkspace);
        }
    }

    private void prepareContainerWorkspace(Path workspaceDir) throws IOException {
        try (var paths = Files.walk(workspaceDir)) {
            paths.forEach(path -> {
                try {
                    Files.setPosixFilePermissions(
                            path,
                            PosixFilePermissions.fromString(Files.isDirectory(path) ? "rwxrwxrwx" : "rw-rw-r--"));
                } catch (UnsupportedOperationException ignored) {
                    // Docker Desktop and Windows provide their own bind-mount permission mapping.
                } catch (IOException e) {
                    throw new WorkspacePermissionException(e);
                }
            });
        } catch (WorkspacePermissionException e) {
            throw (IOException) e.getCause();
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private record ProcessResult(Integer exitCode, boolean timedOut, boolean cancelled, String output) {
        private ProcessResult withOutput(String replacement) {
            return new ProcessResult(exitCode, timedOut, cancelled, replacement);
        }
    }

    private static final class WorkspacePermissionException extends RuntimeException {
        private WorkspacePermissionException(IOException cause) { super(cause); }
    }
}

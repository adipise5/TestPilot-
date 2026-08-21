package com.testpilot.testing.execution;

import com.testpilot.project.entity.CodeFile;
import com.testpilot.testing.entity.GeneratedTest;
import com.testpilot.testing.entity.TestResult;
import com.testpilot.testing.parser.SurefireReportParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class TestExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TestExecutionService.class);
    private final SurefireReportParser surefireReportParser;

    public TestExecutionService(SurefireReportParser surefireReportParser) {
        this.surefireReportParser = surefireReportParser;
    }

    public List<TestResult> executeTests(Long testRunId, List<CodeFile> sourceFiles, List<GeneratedTest> generatedTests) throws Exception {
        Path workspaceDir = Files.createDirectories(Path.of("target", "workspaces", "run-" + testRunId));

        try {
            // 1. Write pom.xml for workspace execution
            writeWorkspacePomXml(workspaceDir.toFile());

            // 2. Write Java source files
            for (CodeFile codeFile : sourceFiles) {
                writeSourceFile(workspaceDir, codeFile.getFilePath(), codeFile.getContent());
            }

            // 3. Write generated test code files
            for (GeneratedTest test : generatedTests) {
                String testFilePath = "src/test/java/" + test.getTestClass().replace('.', '/') + ".java";
                writeSourceFile(workspaceDir, testFilePath, test.getTestCode());
            }

            // 4. Run Maven test process with 60-second timeout
            runMavenTestProcess(workspaceDir.toFile());

            // 5. Parse Surefire XML reports
            File surefireReportsDir = new File(workspaceDir.toFile(), "target/surefire-reports");
            return surefireReportParser.parseReports(testRunId, surefireReportsDir);

        } finally {
            // Cleanup workspace or keep for diagnostics
            log.info("Test execution completed for workspace: {}", workspaceDir.toAbsolutePath());
        }
    }

    private void writeWorkspacePomXml(File workspaceDir) throws IOException {
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

        File pomFile = new File(workspaceDir, "pom.xml");
        try (FileWriter writer = new FileWriter(pomFile)) {
            writer.write(pomContent);
        }
    }

    private void writeSourceFile(Path workspaceDir, String relativePath, String content) throws IOException {
        Path fullPath = workspaceDir.resolve(relativePath);
        Files.createDirectories(fullPath.getParent());
        Files.writeString(fullPath, content);
    }

    private void runMavenTestProcess(File workspaceDir) throws IOException, InterruptedException {
        String mavenCmd = isWindows() ? "mvn.cmd" : "mvn";
        ProcessBuilder processBuilder = new ProcessBuilder(mavenCmd, "clean", "test");
        processBuilder.directory(workspaceDir);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        boolean completedInTime = process.waitFor(60, TimeUnit.SECONDS);

        if (!completedInTime) {
            process.destroyForcibly();
            throw new RuntimeException("Maven test execution timed out after 60 seconds");
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}

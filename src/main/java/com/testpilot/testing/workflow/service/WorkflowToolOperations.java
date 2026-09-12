package com.testpilot.testing.workflow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.testpilot.ai.agent.CodeAnalysisAgent;
import com.testpilot.ai.agent.TestGenerationAgent;
import com.testpilot.ai.dto.CodeAnalysisResponse;
import com.testpilot.ai.dto.TestGenerationResponse;
import com.testpilot.common.exception.InvalidRequestException;
import com.testpilot.common.exception.ResourceNotFoundException;
import com.testpilot.common.validation.RepositoryPathPolicy;
import com.testpilot.failure.agent.FailureAnalysisAgent;
import com.testpilot.failure.agent.FixSuggestionAgent;
import com.testpilot.failure.dto.FailureAnalysisResponse;
import com.testpilot.failure.dto.FixSuggestionResponse;
import com.testpilot.failure.entity.FailureAnalysis;
import com.testpilot.failure.entity.FixSuggestion;
import com.testpilot.failure.repository.FailureAnalysisRepository;
import com.testpilot.failure.repository.FixSuggestionRepository;
import com.testpilot.project.entity.CodeFile;
import com.testpilot.project.service.ProjectSourceService;
import com.testpilot.rag.service.RagService;
import com.testpilot.repository.entity.ConnectedRepository;
import com.testpilot.repository.entity.RepositoryConnectionStatus;
import com.testpilot.repository.entity.RepositoryIngestionStatus;
import com.testpilot.repository.repository.ConnectedRepositoryRepository;
import com.testpilot.repository.repository.RepositoryIngestionRepository;
import com.testpilot.testing.entity.*;
import com.testpilot.testing.execution.TestExecutionOutcome;
import com.testpilot.testing.execution.TestExecutionOutcomeType;
import com.testpilot.testing.execution.TestExecutionService;
import com.testpilot.testing.repository.GeneratedTestRepository;
import com.testpilot.testing.repository.TestResultRepository;
import com.testpilot.testing.repository.TestRunRepository;
import com.testpilot.testing.workflow.entity.WorkflowRun;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class WorkflowToolOperations {

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("(?m)^\\s*package\\s+([a-zA-Z_][\\w.]*)\\s*;");

    private final ObjectMapper objectMapper;
    private final ProjectSourceService projectSourceService;
    private final ConnectedRepositoryRepository connectedRepositoryRepository;
    private final RepositoryIngestionRepository ingestionRepository;
    private final CodeAnalysisAgent codeAnalysisAgent;
    private final TestGenerationAgent testGenerationAgent;
    private final FailureAnalysisAgent failureAnalysisAgent;
    private final FixSuggestionAgent fixSuggestionAgent;
    private final FailureAnalysisRepository failureAnalysisRepository;
    private final FixSuggestionRepository fixSuggestionRepository;
    private final GeneratedTestRepository generatedTestRepository;
    private final TestResultRepository testResultRepository;
    private final TestRunRepository testRunRepository;
    private final TestExecutionService testExecutionService;
    private final RepositoryPathPolicy pathPolicy;
    private final RagService ragService;
    private final WorkflowRunService workflowRunService;

    public WorkflowToolOperations(
            ObjectMapper objectMapper,
            ProjectSourceService projectSourceService,
            ConnectedRepositoryRepository connectedRepositoryRepository,
            RepositoryIngestionRepository ingestionRepository,
            CodeAnalysisAgent codeAnalysisAgent,
            TestGenerationAgent testGenerationAgent,
            FailureAnalysisAgent failureAnalysisAgent,
            FixSuggestionAgent fixSuggestionAgent,
            FailureAnalysisRepository failureAnalysisRepository,
            FixSuggestionRepository fixSuggestionRepository,
            GeneratedTestRepository generatedTestRepository,
            TestResultRepository testResultRepository,
            TestRunRepository testRunRepository,
            TestExecutionService testExecutionService,
            RepositoryPathPolicy pathPolicy,
            RagService ragService,
            WorkflowRunService workflowRunService) {
        this.objectMapper = objectMapper;
        this.projectSourceService = projectSourceService;
        this.connectedRepositoryRepository = connectedRepositoryRepository;
        this.ingestionRepository = ingestionRepository;
        this.codeAnalysisAgent = codeAnalysisAgent;
        this.testGenerationAgent = testGenerationAgent;
        this.failureAnalysisAgent = failureAnalysisAgent;
        this.fixSuggestionAgent = fixSuggestionAgent;
        this.failureAnalysisRepository = failureAnalysisRepository;
        this.fixSuggestionRepository = fixSuggestionRepository;
        this.generatedTestRepository = generatedTestRepository;
        this.testResultRepository = testResultRepository;
        this.testRunRepository = testRunRepository;
        this.testExecutionService = testExecutionService;
        this.pathPolicy = pathPolicy;
        this.ragService = ragService;
        this.workflowRunService = workflowRunService;
    }

    public JsonNode execute(String node, WorkflowRun workflow, JsonNode state) {
        return switch (node) {
            case "intake" -> intake(workflow);
            case "codebase_mapper" -> mapCodebase(workflow);
            case "test_planner" -> planTests(workflow, state);
            case "unit_test_specialist" -> generateProposal(workflow, state, TestLevel.UNIT);
            case "module_test_specialist" -> generateProposal(workflow, state, TestLevel.MODULE);
            case "integration_test_specialist" -> generateProposal(workflow, state, TestLevel.INTEGRATION);
            case "test_reviewer" -> reviewTests(state);
            case "execution_coordinator" -> executeTests(workflow, state);
            case "failure_triage" -> triageFailures(workflow, state);
            case "report" -> createReport(workflow, state);
            default -> throw new InvalidRequestException("Unsupported workflow tool node: " + node);
        };
    }

    public TestRunStatus statusFor(String node) {
        return switch (node) {
            case "intake" -> TestRunStatus.INTAKE;
            case "codebase_mapper" -> TestRunStatus.MAPPING_CODEBASE;
            case "test_planner" -> TestRunStatus.PLANNING_TESTS;
            case "unit_test_specialist" -> TestRunStatus.GENERATING_UNIT_TESTS;
            case "module_test_specialist" -> TestRunStatus.GENERATING_MODULE_TESTS;
            case "integration_test_specialist" -> TestRunStatus.GENERATING_INTEGRATION_TESTS;
            case "test_reviewer" -> TestRunStatus.REVIEWING_TESTS;
            case "execution_coordinator" -> TestRunStatus.RUNNING_TESTS;
            case "failure_triage" -> TestRunStatus.ANALYZING_FAILURES;
            case "report" -> TestRunStatus.REPORTING;
            default -> throw new InvalidRequestException("Unsupported workflow tool node: " + node);
        };
    }

    private JsonNode intake(WorkflowRun workflow) {
        List<CodeFile> sourceFiles = requireSourceFiles(workflow);
        Optional<ConnectedRepository> connected = connectedRepositoryRepository
                .findByProjectId(workflow.getProjectId())
                .filter(repository -> repository.getStatus() == RepositoryConnectionStatus.CONNECTED);

        String revision = connected.map(ConnectedRepository::getSelectedCommitSha)
                .orElseGet(() -> "manual-" + sourceDigest(sourceFiles));
        String buildSystem = connected
                .flatMap(repository -> ingestionRepository.findByConnectedRepositoryIdAndCommitSha(
                        repository.getId(), repository.getSelectedCommitSha()))
                .filter(ingestion -> ingestion.getStatus() == RepositoryIngestionStatus.COMPLETED)
                .map(ingestion -> ingestion.getBuildSystem().name())
                .orElseGet(() -> inferBuildSystem(sourceFiles));

        workflowRunService.recordCommit(workflow.getId(), revision);
        ArrayNode paths = objectMapper.createArrayNode();
        sourceFiles.stream().map(CodeFile::getFilePath).sorted().forEach(paths::add);

        return objectMapper.createObjectNode()
                .put("commit_sha", revision)
                .put("repository_connected", connected.isPresent())
                .put("source_count", sourceFiles.size())
                .put("build_system", buildSystem)
                .set("source_paths", paths);
    }

    private JsonNode mapCodebase(WorkflowRun workflow) {
        List<CodeFile> sourceFiles = requireSourceFiles(workflow);
        CodeAnalysisResponse analysis = codeAnalysisAgent.analyzeCode(sourceFiles);
        Set<String> packages = new TreeSet<>();
        Set<String> modules = new TreeSet<>();
        Set<String> frameworks = new TreeSet<>();
        Set<String> externalResources = new TreeSet<>();

        for (CodeFile file : sourceFiles) {
            Matcher packageMatcher = PACKAGE_PATTERN.matcher(file.getContent());
            if (packageMatcher.find()) packages.add(packageMatcher.group(1));
            String path = file.getFilePath();
            int srcIndex = path.indexOf("/src/");
            modules.add(srcIndex > 0 ? path.substring(0, srcIndex) : "root");
            detectSignals(file.getContent(), frameworks, externalResources);
        }

        ObjectNode codebaseMap = objectMapper.createObjectNode();
        codebaseMap.set("packages", objectMapper.valueToTree(packages));
        codebaseMap.set("modules", objectMapper.valueToTree(modules));
        codebaseMap.set("frameworks", objectMapper.valueToTree(frameworks));
        codebaseMap.set("external_resources", objectMapper.valueToTree(externalResources));
        codebaseMap.put("source_count", sourceFiles.size());

        ObjectNode updates = objectMapper.createObjectNode();
        updates.set("analysis", objectMapper.valueToTree(analysis));
        updates.set("codebase_map", codebaseMap);
        return updates;
    }

    private JsonNode planTests(WorkflowRun workflow, JsonNode state) {
        JsonNode codebaseMap = state.path("codebase_map");
        int sourceCount = codebaseMap.path("source_count").asInt(
                state.path("source_count").asInt(requireSourceFiles(workflow).size()));
        boolean hasFramework = codebaseMap.path("frameworks").size() > 0;
        boolean hasExternalResources = codebaseMap.path("external_resources").size() > 0;

        ArrayNode plan = objectMapper.createArrayNode();
        plan.add(planItem(TestLevel.UNIT, "Isolated class behavior and edge cases"));
        if (sourceCount > 1 || hasFramework) {
            plan.add(planItem(TestLevel.MODULE, "Collaboration inside one application module"));
        }
        if (hasFramework || hasExternalResources) {
            plan.add(planItem(TestLevel.INTEGRATION, "Framework wiring and controlled dependency boundaries"));
        }

        ObjectNode updates = objectMapper.createObjectNode();
        updates.set("test_plan", plan);
        updates.put("approval_required", hasExternalResources);
        updates.put("approval_reason", hasExternalResources
                ? "Integration plan references controlled external resources: "
                        + joinText(codebaseMap.path("external_resources"))
                : "No external-resource approval is required");
        return updates;
    }

    private JsonNode generateProposal(WorkflowRun workflow, JsonNode state, TestLevel level) {
        List<CodeFile> sourceFiles = requireSourceFiles(workflow);
        CodeAnalysisResponse analysis = convertAnalysis(state.path("analysis"));
        String primaryContent = sourceFiles.get(0).getContent();
        String ragContext = ragService.getRelevantContextForTesting(primaryContent);
        TestGenerationResponse generated = testGenerationAgent.generateTests(
                sourceFiles, analysis, ragContext, level);
        String testClass = pathPolicy.validateGeneratedTestClass(generated.testClass());

        ObjectNode proposal = objectMapper.createObjectNode();
        proposal.put("level", level.name());
        proposal.put("source_file", sourceFiles.get(0).getFileName());
        proposal.put("test_class", testClass);
        proposal.put("explanation", generated.explanation());
        proposal.put("test_code", generated.fullTestCode());
        return objectMapper.createObjectNode().set("proposal", proposal);
    }

    private JsonNode reviewTests(JsonNode state) {
        JsonNode proposals = state.path("test_proposals");
        if (!proposals.isArray() || proposals.isEmpty()) {
            throw new InvalidRequestException("Test reviewer received no generated proposals");
        }
        if (proposals.size() > 20) {
            throw new InvalidRequestException("Test reviewer rejected more than 20 generated test classes");
        }

        Set<String> classes = new HashSet<>();
        ArrayNode accepted = objectMapper.createArrayNode();
        ArrayNode checks = objectMapper.createArrayNode();
        for (JsonNode proposal : proposals) {
            String testClass = pathPolicy.validateGeneratedTestClass(proposal.path("test_class").asText());
            String testCode = proposal.path("test_code").asText();
            if (testCode.isBlank() || testCode.length() > 200_000 || !testCode.contains("@Test")) {
                throw new InvalidRequestException("Test reviewer rejected invalid JUnit code for " + testClass);
            }
            if (!classes.add(testClass)) {
                throw new InvalidRequestException("Test reviewer rejected duplicate test class: " + testClass);
            }
            String simpleName = testClass.substring(testClass.lastIndexOf('.') + 1);
            if (!testCode.contains("class " + simpleName)) {
                throw new InvalidRequestException("Generated code does not declare expected class: " + simpleName);
            }
            accepted.add(proposal);
            checks.add("accepted:" + testClass);
        }

        ObjectNode review = objectMapper.createObjectNode()
                .put("approved", true)
                .put("proposal_count", accepted.size());
        review.set("checks", checks);
        ObjectNode updates = objectMapper.createObjectNode();
        updates.set("accepted_proposals", accepted);
        updates.set("review", review);
        return updates;
    }

    private JsonNode executeTests(WorkflowRun workflow, JsonNode state) {
        TestRun testRun = requireTestRun(workflow.getTestRunId());
        List<GeneratedTest> generatedTests = persistAcceptedTests(workflow, state.path("accepted_proposals"));
        List<TestResult> savedResults;

        if (testRun.getExecutionOutcome() == null) {
            TestExecutionOutcome outcome = testExecutionService.executeTests(
                    testRun.getId(),
                    requireSourceFiles(workflow),
                    generatedTests);
            savedResults = testResultRepository.saveAll(outcome.results());
            testRun.recordExecutionOutcome(outcome.type(), outcome.processExitCode(), outcome.output());
            testRunRepository.save(testRun);
        } else {
            savedResults = testResultRepository.findByTestRunId(testRun.getId());
        }

        ArrayNode failedIds = objectMapper.createArrayNode();
        savedResults.stream()
                .filter(result -> result.getStatus() == TestResultStatus.FAILED
                        || result.getStatus() == TestResultStatus.ERROR)
                .map(TestResult::getId)
                .forEach(failedIds::add);

        ObjectNode execution = objectMapper.createObjectNode();
        execution.put("outcome", testRun.getExecutionOutcome().name());
        if (testRun.getProcessExitCode() != null) execution.put("process_exit_code", testRun.getProcessExitCode());
        execution.put("result_count", savedResults.size());
        execution.put("completed_test_process",
                testRun.getExecutionOutcome() == TestExecutionOutcomeType.SUCCESS
                        || testRun.getExecutionOutcome() == TestExecutionOutcomeType.TEST_FAILURE);
        execution.set("failed_result_ids", failedIds);
        return objectMapper.createObjectNode().set("execution", execution);
    }

    private JsonNode triageFailures(WorkflowRun workflow, JsonNode state) {
        List<CodeFile> sourceFiles = requireSourceFiles(workflow);
        String source = sourceFiles.get(0).getContent();
        List<GeneratedTest> generatedTests = generatedTestRepository.findByTestRunId(workflow.getTestRunId());
        String testCode = generatedTests.isEmpty() ? "" : generatedTests.get(0).getTestCode();
        String ragContext = ragService.getRelevantContextForTesting(source);
        ArrayNode triage = objectMapper.createArrayNode();

        for (JsonNode idNode : state.path("execution").path("failed_result_ids")) {
            Long resultId = idNode.asLong();
            TestResult failure = testResultRepository.findById(resultId)
                    .orElseThrow(() -> new ResourceNotFoundException("Failed test result not found: " + resultId));
            FailureAnalysis analysis = failureAnalysisRepository.findByTestResultId(resultId)
                    .orElseGet(() -> createFailureAnalysis(failure, source, testCode, ragContext));
            FixSuggestion fix = fixSuggestionRepository.findByFailureAnalysisId(analysis.getId())
                    .orElseGet(() -> createFixSuggestion(analysis, failure, source, ragContext));

            ObjectNode item = objectMapper.createObjectNode();
            item.put("test_result_id", resultId);
            item.put("failure_analysis_id", analysis.getId());
            item.put("fix_suggestion_id", fix.getId());
            item.put("severity", analysis.getSeverity().name());
            triage.add(item);
        }
        return objectMapper.createObjectNode().set("triage", triage);
    }

    private JsonNode createReport(WorkflowRun workflow, JsonNode state) {
        boolean rejected = "REJECTED".equals(state.path("approval_decision").asText());
        JsonNode execution = state.path("execution");
        boolean executionFailed = !rejected
                && !execution.isMissingNode()
                && !execution.path("completed_test_process").asBoolean(false);
        String terminalStatus = rejected ? "REJECTED" : executionFailed ? "FAILED" : "COMPLETED";
        ObjectNode report = objectMapper.createObjectNode();
        report.put("test_run_id", workflow.getTestRunId());
        report.put("thread_id", workflow.getThreadId());
        report.put("graph_version", workflow.getGraphVersion());
        report.put("commit_sha", state.path("commit_sha").asText(workflow.getCommitSha()));
        report.put("decision", terminalStatus);
        report.put("generated_test_classes", state.path("accepted_proposals").size());
        report.put("execution_outcome", execution.path("outcome").asText(rejected ? "NOT_EXECUTED" : "UNKNOWN"));
        report.put("failed_tests", execution.path("failed_result_ids").size());
        report.put("triaged_failures", state.path("triage").size());
        report.set("planned_levels", state.path("test_plan").deepCopy());
        workflowRunService.finish(workflow.getId(), report, terminalStatus);
        ObjectNode updates = objectMapper.createObjectNode();
        updates.set("report", report);
        updates.put("workflow_status", terminalStatus);
        return updates;
    }

    private List<GeneratedTest> persistAcceptedTests(WorkflowRun workflow, JsonNode proposals) {
        if (!proposals.isArray() || proposals.isEmpty()) {
            throw new InvalidRequestException("Execution coordinator received no reviewed tests");
        }
        List<GeneratedTest> tests = new ArrayList<>();
        for (JsonNode proposal : proposals) {
            String testClass = pathPolicy.validateGeneratedTestClass(proposal.path("test_class").asText());
            GeneratedTest test = generatedTestRepository.findByTestRunIdAndTestClass(
                            workflow.getTestRunId(), testClass)
                    .orElseGet(() -> generatedTestRepository.save(new GeneratedTest(
                            workflow.getTestRunId(),
                            proposal.path("source_file").asText("Source.java"),
                            testClass,
                            proposal.path("test_code").asText(),
                            TestLevel.valueOf(proposal.path("level").asText()))));
            tests.add(test);
        }
        return List.copyOf(tests);
    }

    private FailureAnalysis createFailureAnalysis(
            TestResult failure,
            String source,
            String testCode,
            String ragContext) {
        FailureAnalysisResponse response = failureAnalysisAgent.analyzeFailure(
                source,
                testCode,
                failure.getTestName(),
                failure.getErrorMessage(),
                failure.getStackTrace(),
                ragContext);
        return failureAnalysisRepository.save(new FailureAnalysis(
                failure.getId(),
                response.rootCause(),
                response.severity(),
                response.affectedMethod(),
                response.explanation(),
                response.confidence()));
    }

    private FixSuggestion createFixSuggestion(
            FailureAnalysis analysis,
            TestResult failure,
            String source,
            String ragContext) {
        FailureAnalysisResponse response = new FailureAnalysisResponse(
                analysis.getId(),
                analysis.getTestResultId(),
                analysis.getRootCause(),
                analysis.getSeverity(),
                analysis.getAffectedMethod(),
                analysis.getExplanation(),
                analysis.getConfidence(),
                analysis.getCreatedAt());
        FixSuggestionResponse fix = fixSuggestionAgent.generateFix(
                source, response, failure.getStackTrace(), ragContext);
        return fixSuggestionRepository.save(new FixSuggestion(
                analysis.getId(), fix.originalCode(), fix.suggestedCode(), fix.explanation()));
    }

    private List<CodeFile> requireSourceFiles(WorkflowRun workflow) {
        List<CodeFile> sourceFiles = projectSourceService.getActiveSourceFiles(workflow.getProjectId());
        if (sourceFiles.isEmpty()) {
            throw new InvalidRequestException("Workflow requires at least one Java source file");
        }
        return sourceFiles;
    }

    private TestRun requireTestRun(Long testRunId) {
        return testRunRepository.findById(testRunId)
                .orElseThrow(() -> new ResourceNotFoundException("TestRun not found: " + testRunId));
    }

    private CodeAnalysisResponse convertAnalysis(JsonNode node) {
        try {
            return node == null || node.isMissingNode() || node.isNull()
                    ? null
                    : objectMapper.treeToValue(node, CodeAnalysisResponse.class);
        } catch (Exception e) {
            throw new InvalidRequestException("Workflow analysis state is invalid");
        }
    }

    private ObjectNode planItem(TestLevel level, String objective) {
        return objectMapper.createObjectNode()
                .put("level", level.name())
                .put("objective", objective);
    }

    private void detectSignals(
            String source,
            Set<String> frameworks,
            Set<String> externalResources) {
        if (source.contains("org.springframework") || source.contains("@SpringBootApplication")) frameworks.add("SPRING");
        if (source.contains("JpaRepository") || source.contains("EntityManager") || source.contains("DataSource")) {
            frameworks.add("JPA");
            externalResources.add("DATABASE");
        }
        if (source.contains("Kafka") || source.contains("Jms") || source.contains("Rabbit")) {
            externalResources.add("MESSAGING");
        }
        if (source.contains("WebClient") || source.contains("RestClient") || source.contains("HttpClient")) {
            externalResources.add("HTTP_SERVICE");
        }
    }

    private String inferBuildSystem(List<CodeFile> files) {
        boolean maven = files.stream().anyMatch(file -> file.getFilePath().equals("pom.xml"));
        boolean gradle = files.stream().anyMatch(file -> file.getFilePath().endsWith("build.gradle")
                || file.getFilePath().endsWith("build.gradle.kts"));
        if (maven && gradle) return "MIXED";
        if (maven) return "MAVEN";
        if (gradle) return "GRADLE";
        return "UNKNOWN";
    }

    private String sourceDigest(List<CodeFile> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            files.stream()
                    .sorted(Comparator.comparing(CodeFile::getFilePath))
                    .forEach(file -> {
                        digest.update(file.getFilePath().getBytes(StandardCharsets.UTF_8));
                        digest.update((byte) 0);
                        digest.update(file.getContent().getBytes(StandardCharsets.UTF_8));
                    });
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String joinText(JsonNode values) {
        List<String> items = new ArrayList<>();
        values.forEach(value -> items.add(value.asText()));
        return String.join(", ", items);
    }
}

package com.testpilot.testing.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.ai.agent.TestGenerationAgent;
import com.testpilot.ai.client.LlmClient;
import com.testpilot.auth.security.UserPrincipal;
import com.testpilot.common.exception.InvalidRequestException;
import com.testpilot.project.service.ProjectService;
import com.testpilot.project.service.ProjectSourceService;
import com.testpilot.repository.entity.RepositoryArtifactKind;
import com.testpilot.repository.entity.RepositoryConnectionStatus;
import com.testpilot.repository.entity.RepositoryIngestionStatus;
import com.testpilot.repository.repository.ConnectedRepositoryRepository;
import com.testpilot.repository.repository.RepositoryArtifactRepository;
import com.testpilot.repository.repository.RepositoryIngestionRepository;
import com.testpilot.testing.entity.TestLevel;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class TestPlanningService {
    private final ProjectService projects;
    private final ProjectSourceService manualSources;
    private final ConnectedRepositoryRepository repositories;
    private final RepositoryIngestionRepository ingestions;
    private final RepositoryArtifactRepository artifacts;
    private final LanguageAdapterRegistry adapters;
    private final TestGenerationAgent generator;
    private final LlmClient llm;
    private final TestDraftRepository drafts;
    private final ObjectMapper mapper;

    public TestPlanningService(ProjectService projects, ProjectSourceService manualSources,
            ConnectedRepositoryRepository repositories, RepositoryIngestionRepository ingestions,
            RepositoryArtifactRepository artifacts, LanguageAdapterRegistry adapters,
            TestGenerationAgent generator, LlmClient llm, TestDraftRepository drafts, ObjectMapper mapper) {
        this.projects = projects; this.manualSources = manualSources; this.repositories = repositories;
        this.ingestions = ingestions; this.artifacts = artifacts; this.adapters = adapters;
        this.generator = generator; this.llm = llm; this.drafts = drafts; this.mapper = mapper;
    }

    public record UnsupportedSource(String path, String reason) {}
    public record PlanResponse(String snapshotId, String commitSha, List<TestPlanItem> items,
                               List<UnsupportedSource> unsupported, String provider) {}
    public record SavedDraft(Long id, String snapshotId, JsonNode result) {}
    private record Snapshot(String id, String commitSha, List<SourceInput> targets, List<SourceInput> context) {}

    public PlanResponse plan(Long projectId, UserPrincipal user) {
        projects.findProjectAndVerifyReadAccess(projectId, user);
        return plan(snapshot(projectId));
    }

    private PlanResponse plan(Snapshot snapshot) {
        List<TestPlanItem> items = new ArrayList<>();
        List<UnsupportedSource> unsupported = new ArrayList<>();
        for (SourceInput source : snapshot.targets()) {
            var adapter = adapters.find(source.path());
            if (adapter.isEmpty()) {
                unsupported.add(new UnsupportedSource(source.path(), "No Java, Python or JS/TS generation adapter for this source"));
                continue;
            }
            for (TestLevel level : TestLevel.values()) {
                items.add(adapter.get().plan(source, level, snapshot.context(), snapshot.id()));
            }
        }
        if (items.stream().map(TestPlanItem::outputPath).distinct().count() != items.size()) {
            throw new InvalidRequestException("Plan has conflicting generated output paths");
        }
        var pathPolicy = new com.testpilot.common.validation.RepositoryPathPolicy();
        for (var item : items) {
            pathPolicy.validateRepositoryPath(item.outputPath());
            if (item.outputPath().length() > 512) throw new InvalidRequestException("Planned output path is too long");
        }
        return new PlanResponse(snapshot.id(), snapshot.commitSha(), List.copyOf(items), List.copyOf(unsupported), llm.providerId());
    }

    public SavedDraft generate(Long projectId, String snapshotId, String planId, UserPrincipal user) {
        projects.findProjectAndVerifyWriteAccess(projectId, user);
        Snapshot snapshot = snapshot(projectId);
        if (!snapshot.id().equals(snapshotId)) throw new InvalidRequestException("Repository changed; reload the test plan before generating");
        TestPlanItem item = plan(snapshot).items().stream().filter(p -> p.id().equals(planId)).findFirst()
                .orElseThrow(() -> new InvalidRequestException("Unknown plan item for this project and snapshot"));
        if (!item.applicable()) throw new InvalidRequestException("No detected boundary for this planned test level");
        SourceInput source = snapshot.targets().stream().filter(s -> s.path().equals(item.sourcePath())).findFirst().orElseThrow();
        var adapter = adapters.require(source.path());
        var response = generator.generate(item, source, relevantContext(source, snapshot.context()), "", adapter);
        boolean mock = "mock".equals(llm.providerId());
        var checks = adapter.validate(item, response, mock);
        projects.findProjectAndVerifyWriteAccess(projectId, user);
        if (!snapshot(projectId).id().equals(snapshotId)) throw new InvalidRequestException("Source changed during generation; draft was not saved");
        var result = mapper.createObjectNode();
        result.set("plan", mapper.valueToTree(item));
        result.set("generated", mapper.valueToTree(response));
        result.set("checks", mapper.valueToTree(checks));
        result.put("status", mock ? "MOCK_SCAFFOLD" : "STRUCTURALLY_VALIDATED");
        result.put("provider", llm.providerId());
        result.put("model", llm.modelId());
        result.put("commitSha", snapshot.commitSha());
        result.put("executionStatus", "NOT_EXECUTED");
        result.put("validationNote", "Static heuristic checks only; syntax, imports, behavior and safety are not proven. No compiler or test runner was invoked.");
        result.put("contextNote", "At most 12 context files, 8,000 characters each and 60,000 total; primary source at most 100,000 characters. Context may be partial.");
        return saved(drafts.save(new TestDraft(projectId, snapshot.id(), result.toString())));
    }

    public List<SavedDraft> drafts(Long projectId, UserPrincipal user) {
        projects.findProjectAndVerifyReadAccess(projectId, user);
        return drafts.findTop100ByProjectIdOrderByIdDesc(projectId).stream().map(this::saved).toList();
    }

    public com.testpilot.testing.execution.sandbox.SandboxRequest executionInput(Long projectId, Long draftId, UserPrincipal user) {
        projects.findProjectAndVerifyWriteAccess(projectId, user);
        var draft = drafts.findById(draftId).filter(d -> d.getProjectId().equals(projectId))
                .orElseThrow(() -> new com.testpilot.common.exception.ResourceNotFoundException("Draft not found in this project"));
        var data = saved(draft).result();
        if (!data.path("status").asText().equals("STRUCTURALLY_VALIDATED") || data.path("provider").asText().equals("mock"))
            throw new InvalidRequestException("Mock scaffolds or unvalidated drafts cannot be executed; generate a real validated draft first");
        Snapshot snapshot = snapshot(projectId);
        if (!snapshot.id().equals(draft.getSnapshotId())) throw new InvalidRequestException("Draft snapshot is stale; regenerate against the current source");
        var item = plan(snapshot).items().stream().filter(p -> p.id().equals(data.path("plan").path("id").asText())).findFirst()
                .orElseThrow(() -> new InvalidRequestException("Draft has no matching current plan"));
        try {
            var generated = mapper.treeToValue(data.path("generated"), com.testpilot.ai.dto.TestGenerationResponse.class);
            adapters.require(item.sourcePath()).validate(item, generated, false);
            return new com.testpilot.testing.execution.sandbox.SandboxRequest(item.language(), item.framework(), item.sourcePath(),
                    List.of(new com.testpilot.testing.execution.sandbox.SandboxRequest.TestFile(item.outputPath(), generated.fullTestCode())), snapshot.context());
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new InvalidRequestException("Draft contains invalid generated-test data"); }
    }

    private SavedDraft saved(TestDraft draft) {
        try { return new SavedDraft(draft.getId(), draft.getSnapshotId(), mapper.readTree(draft.getResultJson())); }
        catch (java.io.IOException ex) { throw new IllegalStateException("Stored draft is invalid", ex); }
    }

    private List<SourceInput> relevantContext(SourceInput target, List<SourceInput> context) {
        String dir = target.path().substring(0, target.path().lastIndexOf('/') + 1);
        return context.stream().sorted(Comparator.<SourceInput>comparingInt(s ->
                s.path().endsWith("package.json") || s.path().endsWith("pyproject.toml") || s.path().endsWith("pom.xml") ? 0
                        : s.path().startsWith(dir) ? 1 : 2).thenComparing(SourceInput::path)).toList();
    }

    private Snapshot snapshot(Long projectId) {
        var connected = repositories.findByProjectId(projectId).filter(r -> r.getStatus() == RepositoryConnectionStatus.CONNECTED);
        List<SourceInput> context;
        List<SourceInput> targets;
        String commit = "manual";
        if (connected.isPresent()) {
            var repository = connected.get();
            commit = repository.getSelectedCommitSha();
            var ingestion = ingestions.findByConnectedRepositoryIdAndCommitSha(repository.getId(), commit)
                    .filter(i -> i.getStatus() == RepositoryIngestionStatus.COMPLETED)
                    .orElseThrow(() -> new InvalidRequestException("Complete repository intake before test planning"));
            var catalog = artifacts.findByIngestionIdOrderByPath(ingestion.getId());
            if (catalog.stream().anyMatch(a -> a.getContent() == null || !AdapterSupport.hash(a.getContent()).equals(a.getContentHash()))) {
                throw new InvalidRequestException("Catalog content hash mismatch; refresh repository intake");
            }
            context = catalog.stream().map(a -> new SourceInput(a.getPath(), a.getContent())).toList();
            targets = catalog.stream().filter(a -> a.getKind() == RepositoryArtifactKind.JAVA_SOURCE
                    || a.getKind() == RepositoryArtifactKind.SOURCE_CODE).map(a -> new SourceInput(a.getPath(), a.getContent())).toList();
        } else {
            context = manualSources.getActiveSourceFiles(projectId).stream()
                    .map(f -> new SourceInput(f.getFilePath(), f.getContent())).sorted(Comparator.comparing(SourceInput::path)).toList();
            targets = context;
        }
        if (context.size() > 1000) throw new InvalidRequestException("Planning exceeds the 1,000-file catalog limit");
        StringBuilder fingerprint = new StringBuilder(projectId + ":" + commit);
        context.forEach(s -> fingerprint.append('\0').append(s.path()).append('\0').append(AdapterSupport.hash(s.content())));
        return new Snapshot(AdapterSupport.hash(fingerprint.toString()), commit, targets, context);
    }
}

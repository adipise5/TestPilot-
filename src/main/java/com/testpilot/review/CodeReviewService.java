package com.testpilot.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.auth.security.UserPrincipal;
import com.testpilot.common.exception.*;
import com.testpilot.project.service.ProjectService;
import com.testpilot.repository.service.RepositoryCatalogPolicy;
import com.testpilot.testing.generation.SourceInput;
import com.testpilot.rag.context.SnapshotContextRetriever;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Semaphore;

@Service
public class CodeReviewService {
    static final int BATCH_CHARS = 40_000, BATCH_FILES = 20, CALLS_PER_REVIEW = 8;
    private final ProjectService projects;
    private final ReviewSnapshotService snapshots;
    private final ReviewAgent agent;
    private final CodeReviewRepository store;
    private final ObjectMapper mapper;
    private final SnapshotContextRetriever retriever;
    private final Semaphore capacity = new Semaphore(1);
    public CodeReviewService(ProjectService projects, ReviewSnapshotService snapshots, ReviewAgent agent,
                             CodeReviewRepository store, ObjectMapper mapper, SnapshotContextRetriever retriever) {
        this.projects = projects; this.snapshots = snapshots; this.agent = agent; this.store = store; this.mapper = mapper; this.retriever = retriever;
    }
    public record Plan(String snapshotId, String commitSha, String provider, String model, int totalBatches,
                       int batchesPerReview, List<ReviewReport.FileScope> files) {}
    public record Response(Long id, Long projectId, String status, LocalDateTime startedAt, LocalDateTime completedAt, ReviewReport report) {}
    public record Summary(Long id, String snapshotId, String status, LocalDateTime startedAt, LocalDateTime completedAt) {}
    public Plan plan(Long projectId, UserPrincipal user) {
        projects.findProjectAndVerifyReadAccess(projectId, user);
        var snapshot = snapshots.load(projectId);
        return new Plan(snapshot.id(), snapshot.commitSha(), agent.provider(), agent.model(), batches(snapshot).size(),
                CALLS_PER_REVIEW, scope(snapshot, "PENDING"));
    }
    public Response start(Long projectId, String snapshotId, int offset, UserPrincipal user) {
        projects.findProjectAndVerifyWriteAccess(projectId, user);
        if (!capacity.tryAcquire()) throw new InvalidRequestException("A code review is already running on this server; retry after it finishes");
        try {
            var snapshot = snapshots.load(projectId);
            if (!snapshot.id().equals(snapshotId)) throw new InvalidRequestException("Repository changed; reload review scope before starting");
            var batches = batches(snapshot);
            if (offset < 0 || (offset >= batches.size() && offset != 0)) throw new InvalidRequestException("Invalid review batch offset");
            int end = Math.min(offset + CALLS_PER_REVIEW, batches.size());
            Integer next = end < batches.size() ? end : null;
            var files = new ArrayList<>(scope(snapshot, "DEFERRED"));
            List<ReviewReport.Finding> findings = new ArrayList<>();
            int successful = 0, failed = 0, rejected = 0;
            List<ReviewReport.Retrieval> retrieval = new ArrayList<>();
            var initial = report(snapshot, "RUNNING", offset, next, batches.size(), files, findings, rejected, successful, failed, retrieval);
            CodeReview row = store.saveAndFlush(new CodeReview(projectId, snapshot.id(), encode(initial)));
            if (agent.mock()) {
                for (int i = offset; i < end; i++) mark(files, batches.get(i), "NOT_REVIEWED", "Mock provider cannot perform an AI code review");
            } else {
                for (int i = offset; i < end; i++) {
                    var batch = batches.get(i);
                    try {
                        var context = retriever.retrieve(snapshot.id(), batch, snapshot.files());
                        retrieval.add(new ReviewReport.Retrieval(i, context));
                        var result = agent.review(batch, context);
                        rejected += result.rejected();
                        findings.addAll(result.findings());
                        for (var file : batch) mark(files, List.of(file), result.reviewedPaths().contains(file.path())
                                ? (result.rejected() == 0 ? "REVIEWED" : "REVIEWED_WITH_REJECTIONS") : "NOT_REVIEWED",
                                result.reviewedPaths().contains(file.path()) ? "AI acknowledged this file; cited snippets validated, conclusions unverified"
                                        : "Provider did not acknowledge reviewing this file");
                        successful++;
                    } catch (RuntimeException ex) {
                        failed++;
                        mark(files, batch, "FAILED", "Provider unavailable or response invalid; no findings inferred for this batch");
                    }
                }
            }
            String status = successful == 0 ? "UNAVAILABLE" : files.stream().allMatch(f -> f.status().equals("REVIEWED")) ? "COMPLETED" : "PARTIAL";
            // The report deliberately stays attached to the original snapshot if intake changes mid-call.
            projects.findProjectAndVerifyWriteAccess(projectId, user);
            row.finish(status, encode(report(snapshot, status, offset, next, batches.size(), files, findings, rejected, successful, failed, retrieval)));
            try { return response(store.saveAndFlush(row)); }
            catch (ObjectOptimisticLockingFailureException recovered) { return get(projectId, row.getId(), user); }
        } finally { capacity.release(); }
    }
    public List<Summary> list(Long projectId, UserPrincipal user) {
        projects.findProjectAndVerifyReadAccess(projectId, user);
        return store.findTop50ByProjectIdOrderByIdDesc(projectId).stream()
                .map(r -> new Summary(r.getId(), r.getSnapshotId(), r.getStatus(), r.getStartedAt(), r.getCompletedAt())).toList();
    }
    public Response get(Long projectId, Long id, UserPrincipal user) {
        projects.findProjectAndVerifyReadAccess(projectId, user);
        return response(store.findByIdAndProjectId(id, projectId).orElseThrow(() -> new ResourceNotFoundException("Code review not found in this project")));
    }
    @Scheduled(fixedDelay = 60_000)
    public void recoverInterrupted() {
        for (var row : store.findByStatusAndStartedAtBefore("RUNNING", LocalDateTime.now().minusMinutes(10))) {
            var old = decode(row.getReportJson());
            var files = old.files().stream().map(f -> f.status().equals("EXCLUDED") ? f
                    : new ReviewReport.FileScope(f.path(), f.language(), f.sha256(), f.lines(), "NOT_REVIEWED", "Server/review interrupted; retry explicitly")).toList();
            var report = new ReviewReport(old.schemaVersion(), old.snapshotId(), old.commitSha(), old.provider(), old.model(), "INTERRUPTED",
                    old.batchOffset(), old.nextBatchOffset(), old.totalBatches(), files, List.of(), 0, 0, 0, old.limitations(), old.retrieval());
            row.finish("INTERRUPTED", encode(report));
            try { store.saveAndFlush(row); } catch (ObjectOptimisticLockingFailureException ignored) { }
        }
    }
    List<List<SourceInput>> batches(ReviewSnapshotService.Snapshot snapshot) {
        List<List<SourceInput>> batches = new ArrayList<>();
        List<SourceInput> batch = new ArrayList<>(); int chars = 0;
        for (var file : snapshot.files()) {
            if (snapshots.exclusion(file) != null) continue;
            if (!batch.isEmpty() && (chars + file.content().length() > BATCH_CHARS || batch.size() == BATCH_FILES)) {
                batches.add(List.copyOf(batch)); batch.clear(); chars = 0;
            }
            batch.add(file); chars += file.content().length();
        }
        if (!batch.isEmpty()) batches.add(List.copyOf(batch));
        return List.copyOf(batches);
    }
    private List<ReviewReport.FileScope> scope(ReviewSnapshotService.Snapshot snapshot, String eligibleStatus) {
        return snapshot.files().stream().map(f -> {
            String reason = snapshots.exclusion(f);
            return new ReviewReport.FileScope(f.path(), RepositoryCatalogPolicy.language(f.path()), ReviewSnapshotService.hash(f.content()),
                    (int)f.content().lines().count(), reason == null ? eligibleStatus : "EXCLUDED", reason == null ? "Eligible source; not yet reviewed in this report" : reason);
        }).toList();
    }
    private void mark(List<ReviewReport.FileScope> files, List<SourceInput> batch, String status, String reason) {
        Set<String> paths = new HashSet<>(); batch.forEach(f -> paths.add(f.path()));
        files.replaceAll(f -> paths.contains(f.path()) ? new ReviewReport.FileScope(f.path(), f.language(), f.sha256(), f.lines(), status, reason) : f);
    }
    private ReviewReport report(ReviewSnapshotService.Snapshot snapshot, String status, int offset, Integer next, int batches,
            List<ReviewReport.FileScope> files, List<ReviewReport.Finding> findings, int rejected, int successful, int failed, List<ReviewReport.Retrieval> retrieval) {
        return new ReviewReport("testpilot-review-v2", snapshot.id(), snapshot.commitSha(), agent.provider(), agent.model(), status,
                offset, next, batches, List.copyOf(files), findings.stream().distinct().toList(), rejected, successful, failed, List.of(
                "AI findings and severity are proposals for human review, not verified defects, exploits, fixes or measured performance gains.",
                "Evidence validation proves exact path/line/snippet membership only; it does not establish that an AI conclusion is correct.",
                "Scope is the immutable catalog, not the entire remote repository. Intake-excluded, unsupported and oversized files are not reviewed.",
                "Each request reviews up to 8 batches of 20 files / 40,000 source characters. Continue remaining batches explicitly; previous reports are retained.",
                "Related-symbol excerpts and versioned language standards augment each batch. Lexical matches are not a resolved call graph or a vulnerability database lookup.",
                "A completed review or empty findings list is not proof of correctness, security, performance or absence of defects."), List.copyOf(retrieval));
    }
    private Response response(CodeReview row) { return new Response(row.getId(), row.getProjectId(), row.getStatus(), row.getStartedAt(), row.getCompletedAt(), decode(row.getReportJson())); }
    private ReviewReport decode(String json) {
        try { return mapper.readValue(json, ReviewReport.class); } catch (java.io.IOException ex) { throw new IllegalStateException("Invalid stored code review", ex); }
    }
    private String encode(ReviewReport report) {
        try { return mapper.writeValueAsString(report); } catch (java.io.IOException ex) { throw new IllegalStateException("Unable to save code review", ex); }
    }
}

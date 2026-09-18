package com.testpilot.review;

import com.testpilot.common.exception.InvalidRequestException;
import com.testpilot.common.validation.RepositoryPathPolicy;
import com.testpilot.project.entity.CodeFileOrigin;
import com.testpilot.project.repository.CodeFileRepository;
import com.testpilot.repository.entity.*;
import com.testpilot.repository.repository.*;
import com.testpilot.repository.service.RepositoryCatalogPolicy;
import com.testpilot.testing.generation.SourceInput;
import com.testpilot.testing.report.TestReportBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class ReviewSnapshotService {
    private final ConnectedRepositoryRepository repositories;
    private final RepositoryIngestionRepository ingestions;
    private final RepositoryArtifactRepository artifacts;
    private final CodeFileRepository manual;
    private final RepositoryPathPolicy paths;
    public ReviewSnapshotService(ConnectedRepositoryRepository repositories, RepositoryIngestionRepository ingestions,
            RepositoryArtifactRepository artifacts, CodeFileRepository manual, RepositoryPathPolicy paths) {
        this.repositories = repositories; this.ingestions = ingestions; this.artifacts = artifacts; this.manual = manual; this.paths = paths;
    }
    public record Snapshot(String id, String commitSha, List<SourceInput> files) {}

    @Transactional(readOnly = true)
    public Snapshot load(Long projectId) {
        var repository = repositories.findByProjectId(projectId).filter(r -> r.getStatus() == RepositoryConnectionStatus.CONNECTED);
        String commit = "manual";
        List<SourceInput> files;
        if (repository.isPresent()) {
            var repo = repository.get();
            commit = repo.getSelectedCommitSha();
            var ingestion = ingestions.findByConnectedRepositoryIdAndCommitSha(repo.getId(), commit)
                    .filter(i -> i.getStatus() == RepositoryIngestionStatus.COMPLETED)
                    .orElseThrow(() -> new InvalidRequestException("Complete repository intake before reviewing"));
            var catalog = artifacts.findByIngestionIdOrderByPath(ingestion.getId());
            if (catalog.stream().anyMatch(a -> a.getContent() == null || !hash(a.getContent()).equals(a.getContentHash())))
                throw new InvalidRequestException("Catalog content hash mismatch; refresh repository intake");
            files = catalog.stream().map(a -> new SourceInput(a.getPath(), a.getContent())).toList();
        } else {
            files = manual.findByProjectIdAndOrigin(projectId, CodeFileOrigin.MANUAL).stream()
                    .map(f -> new SourceInput(f.getFilePath(), f.getContent())).sorted(Comparator.comparing(SourceInput::path)).toList();
        }
        if (files.isEmpty()) throw new InvalidRequestException("Add source files or complete repository intake first");
        if (files.size() > 1000 || files.stream().mapToLong(f -> f.content().length()).sum() > 12_000_000)
            throw new InvalidRequestException("Review catalog exceeds 1,000 files or 12 million characters");
        Set<String> seen = new HashSet<>();
        StringBuilder fingerprint = new StringBuilder(projectId + ":" + commit);
        for (var file : files) {
            if (!paths.validateRepositoryPath(file.path()).equals(file.path()) || file.path().length() > 512
                    || !seen.add(file.path())) throw new InvalidRequestException("Invalid or duplicate catalog path");
            fingerprint.append('\0').append(file.path()).append('\0').append(hash(file.content()));
        }
        return new Snapshot(hash(fingerprint.toString()), commit, List.copyOf(files));
    }
    public String exclusion(SourceInput file) {
        if (paths.isSensitiveOrExcluded(file.path())) return "Protected/sensitive path is never sent to the reviewer";
        if (!Set.of("Java", "Python", "JavaScript", "TypeScript").contains(RepositoryCatalogPolicy.language(file.path())))
            return "No Phase 5 review adapter for this file type";
        if (file.content().isBlank()) return "Empty source file";
        if (file.content().length() > 30_000 || file.content().lines().count() > 2000)
            return "File exceeds 30,000 characters or 2,000 lines; no truncated review is claimed";
        return null;
    }
    public static String hash(String text) { return TestReportBuilder.hash(text); }
}

package com.testpilot.rag.service;

import com.testpilot.project.entity.CodeFile;
import com.testpilot.project.entity.Project;
import com.testpilot.repository.entity.RepositoryArtifact;
import com.testpilot.repository.entity.RepositoryArtifactKind;
import com.testpilot.project.repository.ProjectRepository;
import com.testpilot.rag.chunking.SemanticChunk;
import com.testpilot.rag.chunking.SemanticChunkingService;
import com.testpilot.rag.dto.RagIngestionResult;
import com.testpilot.rag.embedding.EmbeddingProvider;
import com.testpilot.rag.entity.DocumentChunk;
import com.testpilot.rag.entity.KnowledgeDocument;
import com.testpilot.rag.entity.RagDocumentType;
import com.testpilot.rag.model.RagScope;
import com.testpilot.rag.repository.DocumentChunkRepository;
import com.testpilot.rag.repository.KnowledgeDocumentRepository;
import com.testpilot.common.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RagIngestionService {

    private static final Pattern SPRING_BOOT_MAVEN_VERSION = Pattern.compile(
            "<artifactId>spring-boot-starter-parent</artifactId>\\s*<version>([^<]+)</version>");
    private static final Pattern SPRING_BOOT_GRADLE_VERSION = Pattern.compile(
            "org\\.springframework\\.boot[^\\n]*version\\s*['\"]([^'\"]+)['\"]");

    private final KnowledgeDocumentRepository documents;
    private final DocumentChunkRepository chunks;
    private final ProjectRepository projects;
    private final SemanticChunkingService chunking;
    private final EmbeddingProvider embeddings;
    private final PgVectorStorageService pgVector;
    private final String ingestionVersion;

    public RagIngestionService(
            KnowledgeDocumentRepository documents,
            DocumentChunkRepository chunks,
            ProjectRepository projects,
            SemanticChunkingService chunking,
            EmbeddingProvider embeddings,
            PgVectorStorageService pgVector,
            @Value("${testpilot.rag.ingestion-version:rag-v2}") String ingestionVersion) {
        this.documents = documents;
        this.chunks = chunks;
        this.projects = projects;
        this.chunking = chunking;
        this.embeddings = embeddings;
        this.pgVector = pgVector;
        this.ingestionVersion = ingestionVersion;
    }

    @Transactional
    public RagIngestionResult indexProject(
            Long projectId,
            String commitSha,
            List<CodeFile> files,
            String frameworkVersion) {
        Project project = projects.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
        RagScope scope = new RagScope(project.getOwnerId(), projectId, commitSha);
        int createdDocs = 0;
        int reusedDocs = 0;
        int createdChunks = 0;
        for (CodeFile file : files) {
            IngestedDocument result = ingest(
                    scope,
                    file.getFileName(),
                    file.getFilePath(),
                    file.getContent(),
                    RagDocumentType.PROJECT_CODE,
                    frameworkVersion == null || frameworkVersion.isBlank() ? "unspecified" : frameworkVersion);
            if (result.created()) createdDocs++; else reusedDocs++;
            createdChunks += result.chunksCreated();
        }
        return new RagIngestionResult(
                createdDocs, reusedDocs, createdChunks, commitSha, embeddings.modelId(), ingestionVersion);
    }

    @Transactional
    public RagIngestionResult indexRepositoryCatalog(
            Long projectId,
            String commitSha,
            List<RepositoryArtifact> artifacts,
            String detectedFramework) {
        Project project = projects.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
        RagScope scope = new RagScope(project.getOwnerId(), projectId, commitSha);
        int createdDocs = 0;
        int reusedDocs = 0;
        int createdChunks = 0;
        for (RepositoryArtifact artifact : artifacts) {
            RagDocumentType type = switch (artifact.getKind()) {
                case JAVA_SOURCE, SOURCE_CODE -> RagDocumentType.PROJECT_CODE;
                case DOCUMENTATION -> RagDocumentType.REPOSITORY_DOCUMENTATION;
                case EXISTING_TEST -> RagDocumentType.PROJECT_TEST;
                case BUILD_MANIFEST, BUILD_CONFIGURATION -> RagDocumentType.BUILD_MANIFEST;
            };
            String frameworkVersion = frameworkVersion(artifact, detectedFramework);
            IngestedDocument result = ingest(
                    scope, fileName(artifact.getPath()), artifact.getPath(), artifact.getContent(), type, frameworkVersion);
            if (result.created()) createdDocs++; else reusedDocs++;
            createdChunks += result.chunksCreated();
        }
        return new RagIngestionResult(
                createdDocs, reusedDocs, createdChunks, commitSha, embeddings.modelId(), ingestionVersion);
    }

    @Transactional
    public KnowledgeDocument ingestGuide(String title, String source, String content) {
        return ingest(
                RagScope.global(), title, source, content, RagDocumentType.TESTING_GUIDE, "unspecified").document();
    }

    private IngestedDocument ingest(
            RagScope scope,
            String title,
            String source,
            String content,
            RagDocumentType type,
            String frameworkVersion) {
        String contentHash = RagHashing.sha256(content);
        var existing = documents
                .findByTenantIdAndProjectIdAndCommitShaAndSourceAndContentHashAndEmbeddingModelAndIngestionVersion(
                        scope.tenantId(), scope.projectId(), scope.commitSha(), source, contentHash,
                        embeddings.modelId(), ingestionVersion);
        KnowledgeDocument document = existing.orElseGet(() -> documents.save(new KnowledgeDocument(
                scope.tenantId(), scope.projectId(), title, source, scope.commitSha(), contentHash,
                type, frameworkVersion, embeddings.modelId(), ingestionVersion, content)));

        int created = 0;
        List<SemanticChunk> semanticChunks = chunking.chunk(source, content);
        for (int ordinal = 0; ordinal < semanticChunks.size(); ordinal++) {
            SemanticChunk semantic = semanticChunks.get(ordinal);
            String chunkHash = RagHashing.sha256(semantic.content());
            String chunkKey = RagHashing.sha256(String.join("\u0000",
                    scope.tenantId().toString(), scope.projectId().toString(), scope.commitSha(), source,
                    contentHash, Integer.toString(ordinal), semantic.kind().name(),
                    semantic.symbol() == null ? "" : semantic.symbol(), ingestionVersion));
            if (chunks.findByChunkKey(chunkKey).isPresent()) continue;

            float[] vector = embeddings.embed(semantic.content());
            DocumentChunk chunk = chunks.save(new DocumentChunk(
                    document.getId(), scope.tenantId(), scope.projectId(), scope.commitSha(), chunkKey,
                    ordinal, semantic.kind(), semantic.symbol(), source, semantic.startLine(), semantic.endLine(),
                    semantic.estimatedTokens(), chunkHash, embeddings.modelId(), semantic.content(), vector));
            pgVector.store(chunk, vector);
            created++;
        }
        return new IngestedDocument(document, existing.isEmpty(), created);
    }

    private record IngestedDocument(KnowledgeDocument document, boolean created, int chunksCreated) {}

    private String frameworkVersion(RepositoryArtifact artifact, String fallback) {
        if (artifact.getKind() != RepositoryArtifactKind.BUILD_MANIFEST) {
            return fallback == null || fallback.isBlank() ? "unspecified" : fallback;
        }
        for (Pattern pattern : List.of(SPRING_BOOT_MAVEN_VERSION, SPRING_BOOT_GRADLE_VERSION)) {
            Matcher matcher = pattern.matcher(artifact.getContent());
            if (matcher.find()) return "spring-boot:" + matcher.group(1).trim();
        }
        return fallback == null || fallback.isBlank() ? "unspecified" : fallback;
    }

    private String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}

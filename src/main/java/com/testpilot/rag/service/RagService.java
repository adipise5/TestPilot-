package com.testpilot.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.common.exception.ResourceNotFoundException;
import com.testpilot.project.entity.Project;
import com.testpilot.project.repository.ProjectRepository;
import com.testpilot.rag.dto.*;
import com.testpilot.rag.entity.KnowledgeDocument;
import com.testpilot.rag.model.RagScope;
import com.testpilot.rag.repository.DocumentChunkRepository;
import com.testpilot.rag.repository.KnowledgeDocumentRepository;
import com.testpilot.rag.repository.RagRetrievalTraceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class RagService {

    private final KnowledgeDocumentRepository documents;
    private final DocumentChunkRepository chunks;
    private final RagRetrievalTraceRepository traces;
    private final ProjectRepository projects;
    private final RagIngestionService ingestion;
    private final HybridRetrievalService retrieval;
    private final ObjectMapper objectMapper;

    public RagService(
            KnowledgeDocumentRepository documents,
            DocumentChunkRepository chunks,
            RagRetrievalTraceRepository traces,
            ProjectRepository projects,
            RagIngestionService ingestion,
            HybridRetrievalService retrieval,
            ObjectMapper objectMapper) {
        this.documents = documents;
        this.chunks = chunks;
        this.traces = traces;
        this.projects = projects;
        this.ingestion = ingestion;
        this.retrieval = retrieval;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public KnowledgeDocumentResponse ingestDocument(CreateKnowledgeDocumentRequest request) {
        return KnowledgeDocumentResponse.fromEntity(
                ingestion.ingestGuide(request.title(), request.source(), request.content()));
    }

    @Transactional(readOnly = true)
    public List<KnowledgeDocumentResponse> getAllDocuments() {
        return documents.findByTenantIdAndProjectIdOrderBySource(0L, 0L).stream()
                .map(KnowledgeDocumentResponse::fromEntity)
                .toList();
    }

    @Transactional
    public void deleteDocument(Long id) {
        KnowledgeDocument document = documents.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Knowledge document not found with id: " + id));
        chunks.deleteAll(chunks.findByDocumentId(document.getId()));
        documents.delete(document);
    }

    public List<RagQueryResult> queryKnowledgeBase(String queryText, int topK) {
        return retrieval.retrieve(RagScope.global(), queryText, topK, null, null).results();
    }

    public RagRetrievalResult retrieveForTesting(
            Long projectId,
            String commitSha,
            String query,
            Long testRunId) {
        Project project = project(projectId);
        return retrieval.retrieve(
                new RagScope(project.getOwnerId(), projectId, commitSha), query, 8, null, testRunId);
    }

    public RagRetrievalResult retrieveForProject(
            Long projectId,
            String commitSha,
            String query,
            int topK,
            Integer tokenBudget) {
        Project project = project(projectId);
        return retrieval.retrieve(
                new RagScope(project.getOwnerId(), projectId, commitSha), query, topK, tokenBudget, null);
    }

    public String getRelevantContextForTesting(String codeSnippet) {
        return retrieval.retrieve(RagScope.global(), codeSnippet, 3, null, null).context();
    }

    @Transactional(readOnly = true)
    public List<RagRetrievalTraceResponse> tracesForTestRun(Long testRunId) {
        return traces.findByTestRunIdOrderByCreatedAtAsc(testRunId).stream()
                .map(trace -> RagRetrievalTraceResponse.from(trace, objectMapper))
                .toList();
    }

    private Project project(Long projectId) {
        return projects.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
    }
}

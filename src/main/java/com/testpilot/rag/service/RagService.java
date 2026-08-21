package com.testpilot.rag.service;

import com.testpilot.ai.client.LlmClient;
import com.testpilot.common.exception.ResourceNotFoundException;
import com.testpilot.rag.dto.CreateKnowledgeDocumentRequest;
import com.testpilot.rag.dto.KnowledgeDocumentResponse;
import com.testpilot.rag.dto.RagQueryResult;
import com.testpilot.rag.entity.DocumentChunk;
import com.testpilot.rag.entity.KnowledgeDocument;
import com.testpilot.rag.repository.DocumentChunkRepository;
import com.testpilot.rag.repository.KnowledgeDocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class RagService {

    private final KnowledgeDocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final ChunkingService chunkingService;
    private final VectorSearchService vectorSearchService;
    private final LlmClient llmClient;

    public RagService(
            KnowledgeDocumentRepository documentRepository,
            DocumentChunkRepository chunkRepository,
            ChunkingService chunkingService,
            VectorSearchService vectorSearchService,
            LlmClient llmClient) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.chunkingService = chunkingService;
        this.vectorSearchService = vectorSearchService;
        this.llmClient = llmClient;
    }

    @Transactional
    public KnowledgeDocumentResponse ingestDocument(CreateKnowledgeDocumentRequest request) {
        KnowledgeDocument doc = new KnowledgeDocument(request.title(), request.source(), request.content());
        KnowledgeDocument savedDoc = documentRepository.save(doc);

        List<String> textChunks = chunkingService.chunkText(request.content());

        for (String chunkText : textChunks) {
            float[] embedding = llmClient.generateEmbedding(chunkText);
            DocumentChunk chunk = new DocumentChunk(savedDoc.getId(), chunkText, embedding);
            chunkRepository.save(chunk);
        }

        return KnowledgeDocumentResponse.fromEntity(savedDoc);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeDocumentResponse> getAllDocuments() {
        return documentRepository.findAll().stream()
                .map(KnowledgeDocumentResponse::fromEntity)
                .toList();
    }

    @Transactional
    public void deleteDocument(Long id) {
        if (!documentRepository.existsById(id)) {
            throw new ResourceNotFoundException("Knowledge document not found with id: " + id);
        }
        chunkRepository.deleteAll(chunkRepository.findByDocumentId(id));
        documentRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public List<RagQueryResult> queryKnowledgeBase(String queryText, int topK) {
        float[] queryEmbedding = llmClient.generateEmbedding(queryText);
        List<DocumentChunk> allChunks = chunkRepository.findAll();
        return vectorSearchService.searchTopK(queryEmbedding, allChunks, topK);
    }

    @Transactional(readOnly = true)
    public String getRelevantContextForTesting(String codeSnippet) {
        List<RagQueryResult> topResults = queryKnowledgeBase(codeSnippet, 3);
        if (topResults.isEmpty()) {
            return "";
        }

        return topResults.stream()
                .map(res -> "- [Relevance: " + String.format("%.2f", res.similarityScore()) + "] " + res.content())
                .collect(Collectors.joining("\n"));
    }
}

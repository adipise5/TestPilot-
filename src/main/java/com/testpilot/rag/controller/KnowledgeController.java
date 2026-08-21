package com.testpilot.rag.controller;

import com.testpilot.rag.dto.CreateKnowledgeDocumentRequest;
import com.testpilot.rag.dto.KnowledgeDocumentResponse;
import com.testpilot.rag.dto.RagQueryRequest;
import com.testpilot.rag.dto.RagQueryResult;
import com.testpilot.rag.service.RagService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final RagService ragService;

    public KnowledgeController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<KnowledgeDocumentResponse> createDocument(@Valid @RequestBody CreateKnowledgeDocumentRequest request) {
        KnowledgeDocumentResponse response = ragService.ingestDocument(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<KnowledgeDocumentResponse>> getAllDocuments() {
        List<KnowledgeDocumentResponse> response = ragService.getAllDocuments();
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteDocument(@PathVariable Long id) {
        ragService.deleteDocument(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/query")
    public ResponseEntity<List<RagQueryResult>> queryKnowledgeBase(@Valid @RequestBody RagQueryRequest request) {
        int topK = request.topK() != null ? request.topK() : 3;
        List<RagQueryResult> results = ragService.queryKnowledgeBase(request.query(), topK);
        return ResponseEntity.ok(results);
    }
}

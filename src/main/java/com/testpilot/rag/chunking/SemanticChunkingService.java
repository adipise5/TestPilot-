package com.testpilot.rag.chunking;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SemanticChunkingService {

    private final JavaSymbolChunker javaChunker;
    private final DocumentSectionChunker documentChunker;

    public SemanticChunkingService(JavaSymbolChunker javaChunker, DocumentSectionChunker documentChunker) {
        this.javaChunker = javaChunker;
        this.documentChunker = documentChunker;
    }

    public List<SemanticChunk> chunk(String source, String content) {
        return source != null && source.toLowerCase().endsWith(".java")
                ? javaChunker.chunk(content)
                : documentChunker.chunk(content);
    }
}

package com.testpilot.rag.service;

import com.testpilot.rag.chunking.DocumentSectionChunker;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class ChunkingService {

    private final DocumentSectionChunker sectionChunker;

    public ChunkingService(DocumentSectionChunker sectionChunker) {
        this.sectionChunker = sectionChunker;
    }

    public List<String> chunkText(String text) {
        return sectionChunker.chunk(text).stream().map(chunk -> chunk.content()).toList();
    }

    public List<String> chunkText(String text, int chunkSize, int chunkOverlap) {
        if (text == null || text.isBlank()) return List.of();
        int safeSize = Math.max(1, chunkSize);
        int safeOverlap = Math.max(0, Math.min(chunkOverlap, safeSize - 1));
        java.util.ArrayList<String> chunks = new java.util.ArrayList<>();
        for (int i = 0; i < text.length(); i += safeSize - safeOverlap) {
            int end = Math.min(text.length(), i + safeSize);
            chunks.add(text.substring(i, end).trim());
            if (end == text.length()) break;
        }
        return List.copyOf(chunks);
    }
}

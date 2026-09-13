package com.testpilot.rag.chunking;

import com.testpilot.rag.entity.RagChunkKind;

public record SemanticChunk(
        RagChunkKind kind,
        String symbol,
        int startLine,
        int endLine,
        String content,
        int estimatedTokens
) {}

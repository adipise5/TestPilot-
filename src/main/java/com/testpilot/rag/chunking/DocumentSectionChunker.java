package com.testpilot.rag.chunking;

import com.testpilot.rag.entity.RagChunkKind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DocumentSectionChunker {

    private static final int MAX_SECTION_CHARS = 4_000;

    public List<SemanticChunk> chunk(String content) {
        if (content == null || content.isBlank()) return List.of();
        String[] lines = content.split("\\R", -1);
        List<SemanticChunk> chunks = new ArrayList<>();
        StringBuilder section = new StringBuilder();
        String heading = null;
        int startLine = 1;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            boolean newHeading = line.trim().matches("^(#{1,6}\\s+.+|[A-Z][A-Za-z0-9 /_-]{2,}:)$");
            if ((newHeading || section.length() + line.length() > MAX_SECTION_CHARS) && !section.isEmpty()) {
                chunks.add(toChunk(heading, startLine, i, section.toString().trim()));
                section.setLength(0);
                startLine = i + 1;
            }
            if (newHeading) heading = line.replaceFirst("^#{1,6}\\s+", "").replaceFirst(":$", "").trim();
            section.append(line).append('\n');
        }
        if (!section.isEmpty()) chunks.add(toChunk(heading, startLine, lines.length, section.toString().trim()));
        return List.copyOf(chunks);
    }

    private SemanticChunk toChunk(String heading, int start, int end, String content) {
        return new SemanticChunk(
                RagChunkKind.DOCUMENT_SECTION,
                heading,
                start,
                end,
                content,
                Math.max(1, (content.length() + 3) / 4));
    }
}

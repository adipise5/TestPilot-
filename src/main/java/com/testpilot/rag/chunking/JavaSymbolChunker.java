package com.testpilot.rag.chunking;

import com.testpilot.rag.entity.RagChunkKind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class JavaSymbolChunker {

    private static final int MAX_CHUNK_CHARS = 8_000;
    private static final Pattern TYPE = Pattern.compile(
            "(?:public\\s+|protected\\s+|private\\s+|abstract\\s+|final\\s+|sealed\\s+|non-sealed\\s+)*"
                    + "(?:class|interface|enum|record)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
    private static final Pattern METHOD = Pattern.compile(
            "(?:public|protected|private|static|final|abstract|synchronized|native|default|strictfp|\\s)+"
                    + "[A-Za-z_$][A-Za-z0-9_$<>, ?\\[\\].]*\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\([^;]*\\)"
                    + "(?:\\s+throws\\s+[^\\{]+)?\\s*\\{");

    public List<SemanticChunk> chunk(String source) {
        if (source == null || source.isBlank()) return List.of();
        String[] lines = source.split("\\R", -1);
        String header = header(lines);
        List<SemanticChunk> chunks = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            Matcher type = TYPE.matcher(lines[i]);
            if (type.find()) {
                int end = declarationEnd(lines, i);
                String body = bounded(join(lines, i, end));
                chunks.add(chunk(RagChunkKind.JAVA_TYPE, type.group(1), i + 1, end + 1, header + body));
            }

            Matcher method = METHOD.matcher(lines[i]);
            if (method.find() && !isControlFlow(method.group(1))) {
                int end = declarationEnd(lines, i);
                String body = bounded(join(lines, i, end));
                chunks.add(chunk(RagChunkKind.JAVA_METHOD, method.group(1), i + 1, end + 1, header + body));
            }
        }

        if (chunks.isEmpty()) {
            chunks.add(chunk(RagChunkKind.FALLBACK, null, 1, lines.length, bounded(source)));
        }
        return List.copyOf(chunks);
    }

    private int declarationEnd(String[] lines, int start) {
        int depth = 0;
        boolean opened = false;
        for (int i = start; i < lines.length; i++) {
            for (int j = 0; j < lines[i].length(); j++) {
                char current = lines[i].charAt(j);
                if (current == '{') {
                    opened = true;
                    depth++;
                } else if (current == '}') {
                    depth--;
                }
            }
            if (opened && depth <= 0) return i;
        }
        return lines.length - 1;
    }

    private String header(String[] lines) {
        StringBuilder value = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("package ") || trimmed.startsWith("import ")) {
                value.append(line).append('\n');
            }
        }
        return value.isEmpty() ? "" : value.append('\n').toString();
    }

    private String join(String[] lines, int start, int end) {
        StringBuilder value = new StringBuilder();
        for (int i = start; i <= end; i++) value.append(lines[i]).append('\n');
        return value.toString().trim();
    }

    private String bounded(String value) {
        if (value.length() <= MAX_CHUNK_CHARS) return value;
        return value.substring(0, MAX_CHUNK_CHARS) + "\n[CHUNK TRUNCATED]";
    }

    private SemanticChunk chunk(
            RagChunkKind kind, String symbol, int startLine, int endLine, String content) {
        return new SemanticChunk(
                kind, symbol, startLine, endLine, content, Math.max(1, (content.length() + 3) / 4));
    }

    private boolean isControlFlow(String symbol) {
        return List.of("if", "for", "while", "switch", "catch", "try").contains(symbol);
    }
}

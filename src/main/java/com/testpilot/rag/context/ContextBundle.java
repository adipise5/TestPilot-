package com.testpilot.rag.context;

import java.util.List;

/** Persisted retrieval provenance, never an assertion that all contextual code was reviewed. */
public record ContextBundle(String snapshotId, String indexVersion, String queryHash, String standardsVersion,
                            List<LanguageStandards.Rule> standards, List<Snippet> snippets,
                            int candidates, int usedCharacters, List<String> limitations) {
    public record Snippet(String path, String symbol, int startLine, int endLine, String content,
                          String sourceHash, String snippetHash, String reason, int score) {}
}

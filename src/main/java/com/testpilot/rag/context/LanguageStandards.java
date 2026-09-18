package com.testpilot.rag.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class LanguageStandards {
    public record Rule(String id, String language, String category, String guidance, String sourceUrl) {}
    public record Catalog(String version, List<Rule> rules) {}
    private final Catalog catalog;
    public LanguageStandards(ObjectMapper mapper) {
        try (var stream = getClass().getResourceAsStream("/standards/review-v1.json")) {
            if (stream == null) throw new IllegalStateException("Missing language standards");
            catalog = mapper.readValue(stream, Catalog.class);
            if (catalog.rules().stream().map(Rule::id).distinct().count() != catalog.rules().size())
                throw new IllegalStateException("Duplicate standard identity");
        } catch (java.io.IOException ex) { throw new IllegalStateException("Invalid language standards", ex); }
    }
    public String version() { return catalog.version(); }
    public List<Rule> forLanguages(Set<String> languages) { return catalog.rules().stream().filter(r -> languages.contains(r.language())).toList(); }
}

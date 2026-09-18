package com.testpilot.rag.context;

import com.testpilot.common.exception.InvalidRequestException;
import com.testpilot.common.validation.RepositoryPathPolicy;
import com.testpilot.repository.service.RepositoryCatalogPolicy;
import com.testpilot.testing.generation.SourceInput;
import com.testpilot.testing.report.TestReportBuilder;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;

/** Deterministic lexical symbol retrieval over one authorized snapshot; no global cache or external reads. */
@Component
public class SnapshotContextRetriever {
    public static final String VERSION = "snapshot-symbol-lexical-v1";
    public static final int MAX_SNIPPETS = 6, MAX_CONTEXT_CHARS = 12_000;
    private static final Pattern WORD = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]{2,}");
    private static final Pattern TYPE = Pattern.compile("\\b(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern PYTHON = Pattern.compile("^\\s*(?:async\\s+)?def\\s+([A-Za-z_]\\w*)\\s*\\(");
    private static final Pattern JS = Pattern.compile("\\b(?:function|const|let)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern JAVA = Pattern.compile("\\b(?:public|protected|private|static)\\s+(?:(?:static|final|synchronized)\\s+)*[\\w<>\\[\\].?]+\\s+([A-Za-z_$][\\w$]*)\\s*\\(");
    private static final Set<String> COMMON = Set.of("return", "public", "private", "protected", "static", "void", "class", "const", "let", "function", "export", "import", "from", "def", "self", "this", "true", "false", "null", "None", "String", "int", "new", "number", "string", "async", "await", "default", "throws");
    private final LanguageStandards standards;
    private final RepositoryPathPolicy paths;
    public SnapshotContextRetriever(LanguageStandards standards, RepositoryPathPolicy paths) { this.standards = standards; this.paths = paths; }
    private record Chunk(SourceInput file, String symbol, int start, int end, String text, Set<String> terms) {}
    private record Ranked(Chunk chunk, int score, String reason) {}

    public ContextBundle retrieve(String snapshotId, List<SourceInput> primary, List<SourceInput> catalog) {
        if (snapshotId == null || snapshotId.isBlank() || catalog.size() > 1000
                || catalog.stream().mapToLong(f -> f.content().length()).sum() > 12_000_000)
            throw new InvalidRequestException("Invalid retrieval snapshot or budget");
        Map<String, SourceInput> source = new TreeMap<>();
        for (var file : catalog) {
            if (!paths.validateRepositoryPath(file.path()).equals(file.path()) || source.put(file.path(), file) != null)
                throw new InvalidRequestException("Invalid or duplicate retrieval source");
        }
        Set<String> primaryPaths = new HashSet<>(), query = new HashSet<>(), languages = new HashSet<>();
        StringBuilder queryIdentity = new StringBuilder(snapshotId);
        for (var file : primary) {
            if (!file.equals(source.get(file.path())) || paths.isSensitiveOrExcluded(file.path()))
                throw new InvalidRequestException("Primary source is not in the authorized retrieval snapshot");
            primaryPaths.add(file.path()); query.addAll(terms(mask(file.content(), RepositoryCatalogPolicy.language(file.path()))));
            languages.add(RepositoryCatalogPolicy.language(file.path()));
            queryIdentity.append('\0').append(file.path()).append('\0').append(hash(file.content()));
        }
        List<Ranked> ranked = new ArrayList<>();
        for (var file : source.values()) {
            String language = RepositoryCatalogPolicy.language(file.path());
            if (primaryPaths.contains(file.path()) || paths.isSensitiveOrExcluded(file.path())
                    || !Set.of("Java", "Python", "JavaScript", "TypeScript").contains(language)
                    || file.content().length() > 100_000 || file.content().lines().count() > 5000) continue;
            String base = file.path().substring(file.path().lastIndexOf('/')+1).replaceFirst("\\.[^.]+$", "");
            for (var chunk : chunks(file, language)) {
                boolean symbol = chunk.symbol() != null && query.contains(chunk.symbol());
                boolean module = query.contains(base);
                int overlap = (int)chunk.terms().stream().filter(query::contains).count();
                // Generic overlap alone is insufficient. Require a named symbol or module hint.
                if (!symbol && !module) continue;
                int score = (symbol ? 100 : 0) + (module ? 50 : 0) + Math.min(overlap, 20);
                ranked.add(new Ranked(chunk, score, symbol ? "SYMBOL_NAME_MATCH (lexical candidate, not resolved call)" : "MODULE_NAME_MATCH (lexical candidate, not resolved import)"));
            }
        }
        ranked.sort(Comparator.comparingInt(Ranked::score).reversed().thenComparing(r -> r.chunk().file().path()).thenComparingInt(r -> r.chunk().start()));
        List<ContextBundle.Snippet> snippets = new ArrayList<>(); int chars = 0;
        Set<String> selected = new HashSet<>();
        for (var candidate : ranked) {
            var chunk = candidate.chunk();
            if (snippets.size() == MAX_SNIPPETS) break;
            if (chars + chunk.text().length() > MAX_CONTEXT_CHARS || !selected.add(chunk.file().path()+":"+chunk.start())) continue;
            snippets.add(new ContextBundle.Snippet(chunk.file().path(), chunk.symbol(), chunk.start(), chunk.end(), chunk.text(),
                    hash(chunk.file().content()), hash(chunk.text()), candidate.reason(), candidate.score()));
            chars += chunk.text().length();
        }
        return new ContextBundle(snapshotId, VERSION, hash(queryIdentity.toString()), standards.version(), standards.forLanguages(languages),
                List.copyOf(snippets), ranked.size(), chars, List.of(
                "Lexical declarations/name overlap are retrieval hints, not a resolved import or call graph; aliases, dynamic calls and overloads may be missed.",
                "Context is limited to six exact excerpts / 12,000 characters from supported files in this snapshot. Oversized, sensitive and unsupported files are omitted.",
                "Context excerpts do not establish whole-file review coverage. Standards are a small versioned project baseline, not an exhaustive security standard."));
    }
    private List<Chunk> chunks(SourceInput file, String language) {
        String[] lines = file.content().lines().toArray(String[]::new);
        String[] clean = mask(file.content(), language).lines().toArray(String[]::new);
        List<Chunk> chunks = new ArrayList<>();
        for (int i=0; i<Math.min(lines.length, clean.length) && chunks.size()<100; i++) {
            String symbol = null;
            for (var pattern : List.of(TYPE, language.equals("Python") ? PYTHON : language.equals("Java") ? JAVA : JS)) {
                var match = pattern.matcher(clean[i]); if (match.find()) { symbol = match.group(1); break; }
            }
            if (symbol == null) continue;
            StringBuilder text = new StringBuilder(); int end = i;
            for (int j=i; j<Math.min(lines.length,i+40); j++) {
                if (text.length() + lines[j].length()+1 > 3000) break;
                if (!text.isEmpty()) text.append('\n'); text.append(lines[j]); end=j;
            }
            if (!text.isEmpty()) chunks.add(new Chunk(file, symbol, i+1, end+1, text.toString(), terms(String.join("\n", Arrays.copyOfRange(clean,i,end+1)))));
        }
        return chunks;
    }
    private Set<String> terms(String code) {
        Set<String> terms = new HashSet<>(); var matcher = WORD.matcher(code);
        while (matcher.find()) if (!COMMON.contains(matcher.group())) terms.add(matcher.group());
        return terms;
    }
    /** Mask comments and quoted strings while preserving line boundaries; deliberately not a compiler parser. */
    static String mask(String code, String language) {
        StringBuilder out = new StringBuilder(code.length());
        char quote=0; boolean line=false, block=false, triple=false, escape=false;
        for (int i=0; i<code.length(); i++) {
            char c=code.charAt(i), next=i+1<code.length()?code.charAt(i+1):0;
            if (c=='\n' || c=='\r') { out.append(c); line=false; if (quote!=0 && !triple && quote!='`') quote=0; escape=false; continue; }
            if (line) { out.append(' '); continue; }
            if (block) { out.append(' '); if(c=='*' && next=='/') { out.append(' '); i++; block=false; } continue; }
            if (quote!=0) {
                out.append(' ');
                if (escape) { escape=false; continue; }
                if (c=='\\') { escape=true; continue; }
                if (c==quote && (!triple || (next==quote && i+2<code.length() && code.charAt(i+2)==quote))) {
                    if (triple) { out.append("  "); i+=2; } quote=0; triple=false;
                }
                continue;
            }
            if (language.equals("Python") && c=='#') { line=true; out.append(' '); }
            else if (!language.equals("Python") && c=='/' && (next=='/' || next=='*')) { line=next=='/'; block=next=='*'; out.append("  "); i++; }
            else if (c=='\'' || c=='"' || c=='`') {
                quote=c; triple=language.equals("Python") && next==c && i+2<code.length() && code.charAt(i+2)==c;
                out.append(' '); if(triple) { out.append("  "); i+=2; }
            } else out.append(c);
        }
        return out.toString();
    }
    public static String hash(String value) { return TestReportBuilder.hash(value); }
}

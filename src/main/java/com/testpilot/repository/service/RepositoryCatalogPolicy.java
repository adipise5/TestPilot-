package com.testpilot.repository.service;

import com.testpilot.common.exception.InvalidRequestException;
import com.testpilot.common.validation.RepositoryPathPolicy;
import com.testpilot.repository.connector.RepositoryFileContent;
import com.testpilot.repository.connector.RepositoryTreeEntry;
import com.testpilot.repository.entity.BuildSystem;
import com.testpilot.repository.entity.RepositoryArtifactKind;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class RepositoryCatalogPolicy {

    private static final java.util.Map<String, String> LANGUAGES = java.util.Map.ofEntries(
            java.util.Map.entry("java", "Java"), java.util.Map.entry("py", "Python"),
            java.util.Map.entry("js", "JavaScript"), java.util.Map.entry("jsx", "JavaScript"),
            java.util.Map.entry("mjs", "JavaScript"), java.util.Map.entry("cjs", "JavaScript"),
            java.util.Map.entry("ts", "TypeScript"), java.util.Map.entry("tsx", "TypeScript"),
            java.util.Map.entry("go", "Go"), java.util.Map.entry("rs", "Rust"),
            java.util.Map.entry("c", "C"), java.util.Map.entry("h", "C/C++"),
            java.util.Map.entry("cpp", "C++"), java.util.Map.entry("hpp", "C++"),
            java.util.Map.entry("cc", "C++"), java.util.Map.entry("cs", "C#"),
            java.util.Map.entry("kt", "Kotlin"), java.util.Map.entry("kts", "Kotlin"),
            java.util.Map.entry("rb", "Ruby"), java.util.Map.entry("php", "PHP"),
            java.util.Map.entry("swift", "Swift"), java.util.Map.entry("scala", "Scala"),
            java.util.Map.entry("dart", "Dart"), java.util.Map.entry("ex", "Elixir"),
            java.util.Map.entry("exs", "Elixir"), java.util.Map.entry("r", "R"),
            java.util.Map.entry("lua", "Lua"), java.util.Map.entry("sh", "Shell"),
            java.util.Map.entry("sql", "SQL"), java.util.Map.entry("html", "HTML"),
            java.util.Map.entry("css", "CSS"), java.util.Map.entry("scss", "SCSS"),
            java.util.Map.entry("vue", "Vue"), java.util.Map.entry("svelte", "Svelte"));
    private static final java.util.Set<String> MANIFESTS = java.util.Set.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "package.json", "pyproject.toml",
            "requirements.txt", "setup.py", "setup.cfg", "pipfile", "cargo.toml", "go.mod",
            "gemfile", "composer.json", "pubspec.yaml", "cmakelists.txt", "makefile");
    private static final java.util.Set<String> EXCLUDED_SEGMENTS = java.util.Set.of(
            ".git", ".idea", ".vscode", "node_modules", "vendor", "target", "build", "dist",
            "out", "bin", "obj", "__pycache__", ".venv", "venv", ".next", ".nuxt",
            ".cache", ".pytest_cache", ".mypy_cache", "coverage", "htmlcov", "generated",
            "generated-sources", "mutants", "test-results", "playwright-report", ".gradle");

    public static String language(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return LANGUAGES.getOrDefault(lower.substring(lower.lastIndexOf('.') + 1), "Other / context");
    }

    public List<com.testpilot.repository.dto.RepositorySelectionResponse.BuildContext> buildContexts(List<CatalogArtifact> artifacts) {
        return artifacts.stream().filter(a -> a.kind() == RepositoryArtifactKind.BUILD_MANIFEST)
                .map(a -> {
                    String name = a.path().substring(a.path().lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                    String ecosystem = "Other";
                    String hint = "No test command inferred";
                    switch (name) {
                        case "pom.xml" -> { ecosystem = "Maven"; hint = "mvn test (candidate; not validated)"; }
                        case "build.gradle", "build.gradle.kts" -> { ecosystem = "Gradle"; hint = "gradle test (candidate; not validated)"; }
                        case "package.json" -> {
                            ecosystem = "Node.js";
                            try {
                                var test = new com.fasterxml.jackson.databind.ObjectMapper().readTree(a.content()).path("scripts").path("test");
                                if (test.isTextual() && !test.asText().isBlank()) hint = "npm test (scripts.test declared; not validated)";
                            } catch (java.io.IOException ignored) { hint = "Invalid JSON manifest; no command inferred"; }
                        }
                        case "pyproject.toml", "requirements.txt", "setup.py", "setup.cfg", "pipfile" -> {
                            ecosystem = "Python";
                            if (a.content().toLowerCase(Locale.ROOT).contains("pytest")) hint = "python -m pytest (candidate; not validated)";
                        }
                        case "go.mod" -> { ecosystem = "Go"; hint = "go test ./... (candidate; not validated)"; }
                        case "cargo.toml" -> { ecosystem = "Rust"; hint = "cargo test (candidate; not validated)"; }
                        default -> { }
                    }
                    return new com.testpilot.repository.dto.RepositorySelectionResponse.BuildContext(a.path(), ecosystem, hint);
                }).toList();
    }

    public String exclusionReason(RepositoryTreeEntry entry) {
        if (!entry.isFile()) return "Not a regular file (directory, symlink or submodule)";
        if (entry.path() == null || entry.path().length() > 512) return "Missing path or exceeds 512-character catalog path limit";
        if (entry.size() > MAX_FILE_BYTES) return "Exceeds 512 KiB file limit";
        if (pathPolicy.isSensitiveOrExcluded(entry.path())) return "Protected, sensitive, dependency or build path";
        String lower = entry.path().toLowerCase(Locale.ROOT);
        for (String segment : lower.split("/")) {
            if (EXCLUDED_SEGMENTS.contains(segment)) return "Generated, dependency, cache or build directory";
        }
        if (lower.endsWith(".min.js") || lower.endsWith(".min.css") || lower.endsWith(".map")
                || lower.endsWith(".lock") || lower.endsWith("-lock.json")
                || lower.endsWith(".g.cs") || lower.endsWith(".generated.ts")
                || lower.endsWith("_pb2.py") || lower.endsWith(".pb.go")) return "Generated or lock file";
        return null;
    }

    public static final int MAX_CATALOG_FILES = 1_000;
    public static final long MAX_CATALOG_BYTES = 10L * 1024 * 1024;
    public static final long MAX_FILE_BYTES = 512L * 1024;

    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AWS_ACCESS_KEY = Pattern.compile("(?<![A-Z0-9])AKIA[A-Z0-9]{16}(?![A-Z0-9])");
    private static final Pattern GITHUB_TOKEN = Pattern.compile("(?<![A-Za-z0-9])gh[pousr]_[A-Za-z0-9_]{20,}");

    private final RepositoryPathPolicy pathPolicy;

    public RepositoryCatalogPolicy(RepositoryPathPolicy pathPolicy) {
        this.pathPolicy = pathPolicy;
    }

    public Optional<RepositoryArtifactKind> classify(RepositoryTreeEntry entry) {
        if (exclusionReason(entry) != null) {
            return Optional.empty();
        }
        String path = pathPolicy.validateRepositoryPath(entry.path());
        String lower = path.toLowerCase(Locale.ROOT);
        if (!language(path).equals("Other / context") && lower.matches("(^|.*/)(fixtures|test-fixtures|benchmark)/.*")) {
            return Optional.of(RepositoryArtifactKind.EXISTING_TEST);
        }
        if (lower.matches("(^|.*/)src/main/java/.+\\.java$")) {
            return Optional.of(RepositoryArtifactKind.JAVA_SOURCE);
        }
        if (lower.matches("(^|.*/)src/test/java/.+\\.java$")) {
            return Optional.of(RepositoryArtifactKind.EXISTING_TEST);
        }
        String fileName = lower.substring(lower.lastIndexOf('/') + 1);
        if (MANIFESTS.contains(fileName) || fileName.endsWith(".csproj") || fileName.endsWith(".fsproj")) {
            return Optional.of(RepositoryArtifactKind.BUILD_MANIFEST);
        }
        if (fileName.equals("settings.gradle") || fileName.equals("settings.gradle.kts")
                || fileName.equals("gradle.properties") || fileName.equals("maven-wrapper.properties")
                || fileName.equals("tsconfig.json") || fileName.equals("pytest.ini")
                || fileName.startsWith("jest.config.") || fileName.startsWith("vitest.config.")) {
            return Optional.of(RepositoryArtifactKind.BUILD_CONFIGURATION);
        }
        if (!language(path).equals("Other / context")) {
            boolean test = lower.matches("(^|.*/)(tests?|__tests__|spec)/.*")
                    || fileName.startsWith("test_") || fileName.matches(".*[._](test|spec)[.].*")
                    || fileName.endsWith("_test.go") || fileName.endsWith("test.java")
                    || fileName.endsWith("tests.java");
            return Optional.of(test ? RepositoryArtifactKind.EXISTING_TEST : RepositoryArtifactKind.SOURCE_CODE);
        }
        if (fileName.equals("readme.md") || fileName.equals("readme.rst") || fileName.equals("readme.txt")) {
            return Optional.of(RepositoryArtifactKind.DOCUMENTATION);
        }
        return Optional.empty();
    }

    public CatalogArtifact decode(
            RepositoryFileContent file,
            RepositoryArtifactKind kind) {
        if (file.content().length > MAX_FILE_BYTES) {
            throw new InvalidRequestException("Repository file exceeds the 512 KiB ingestion limit: " + file.path());
        }
        String content = decodeUtf8(file.content(), file.path());
        String header = content.substring(0, Math.min(content.length(), 2048));
        if (Pattern.compile("(?im)^\\s*(?://|#|/\\*|\\*|<!--)\\s*(?:code generated.*do not edit|@generated\\b|auto-generated\\b|automatically generated\\b)")
                .matcher(header).find()) {
            throw new InvalidRequestException("Generated content was excluded: " + file.path());
        }
        if (containsSecret(content)) {
            throw new InvalidRequestException("A credential-like file was excluded from ingestion: " + file.path());
        }
        return new CatalogArtifact(
                pathPolicy.validateRepositoryPath(file.path()),
                file.objectSha(),
                sha256(file.content()),
                kind,
                file.content().length,
                content);
    }

    public BuildSystem detectBuildSystem(List<CatalogArtifact> artifacts) {
        boolean maven = artifacts.stream().anyMatch(artifact ->
                artifact.kind() == RepositoryArtifactKind.BUILD_MANIFEST
                        && artifact.path().toLowerCase(Locale.ROOT).endsWith("pom.xml"));
        boolean gradle = artifacts.stream().anyMatch(artifact -> {
            String lower = artifact.path().toLowerCase(Locale.ROOT);
            return artifact.kind() == RepositoryArtifactKind.BUILD_MANIFEST
                    && (lower.endsWith("build.gradle") || lower.endsWith("build.gradle.kts"));
        });
        if (maven && gradle) return BuildSystem.MIXED;
        if (maven) return BuildSystem.MAVEN;
        if (gradle) return BuildSystem.GRADLE;
        return BuildSystem.UNKNOWN;
    }

    public String catalogHash(List<CatalogArtifact> artifacts) {
        String canonical = artifacts.stream()
                .sorted(Comparator.comparing(CatalogArtifact::path))
                .map(artifact -> artifact.path() + ":" + artifact.contentHash())
                .reduce("", (left, right) -> left + right + "\n");
        return sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeUtf8(byte[] bytes, String path) {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            if (decoded.toString().indexOf('\0') >= 0) {
                throw new InvalidRequestException("Binary repository file was excluded: " + path);
            }
            return decoded.toString();
        } catch (CharacterCodingException e) {
            throw new InvalidRequestException("Non-UTF-8 repository file was excluded: " + path);
        }
    }

    private boolean containsSecret(String content) {
        return PRIVATE_KEY.matcher(content).find()
                || AWS_ACCESS_KEY.matcher(content).find()
                || GITHUB_TOKEN.matcher(content).find()
                || Pattern.compile("github_pat_[A-Za-z0-9_]{20,}").matcher(content).find();
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record CatalogArtifact(
            String path,
            String objectSha,
            String contentHash,
            RepositoryArtifactKind kind,
            long sizeBytes,
            String content) {}
}

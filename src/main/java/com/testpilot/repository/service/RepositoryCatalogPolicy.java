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
        if (!entry.isFile() || entry.size() > MAX_FILE_BYTES || pathPolicy.isSensitiveOrExcluded(entry.path())) {
            return Optional.empty();
        }
        String path = pathPolicy.validateRepositoryPath(entry.path());
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.matches("(^|.*/)src/main/java/.+\\.java$")) {
            return Optional.of(RepositoryArtifactKind.JAVA_SOURCE);
        }
        if (lower.matches("(^|.*/)src/test/java/.+\\.java$")) {
            return Optional.of(RepositoryArtifactKind.EXISTING_TEST);
        }
        String fileName = lower.substring(lower.lastIndexOf('/') + 1);
        if (fileName.equals("pom.xml") || fileName.equals("build.gradle") || fileName.equals("build.gradle.kts")) {
            return Optional.of(RepositoryArtifactKind.BUILD_MANIFEST);
        }
        if (fileName.equals("settings.gradle") || fileName.equals("settings.gradle.kts")
                || fileName.equals("gradle.properties") || fileName.equals("maven-wrapper.properties")) {
            return Optional.of(RepositoryArtifactKind.BUILD_CONFIGURATION);
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
                || GITHUB_TOKEN.matcher(content).find();
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

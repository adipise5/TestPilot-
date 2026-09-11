package com.testpilot.common.validation;

import com.testpilot.common.exception.InvalidRequestException;
import org.springframework.stereotype.Component;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class RepositoryPathPolicy {

    private static final Pattern WINDOWS_DRIVE = Pattern.compile("^[A-Za-z]:.*");
    private static final Pattern JAVA_CLASS_NAME = Pattern.compile(
            "^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*$"
    );
    private static final Set<String> SENSITIVE_FILE_NAMES = Set.of(
            ".env", ".npmrc", ".pypirc", "credentials", "credentials.json",
            "id_rsa", "id_ed25519", "secrets.yml", "secrets.yaml"
    );

    public String validateSourcePath(String fileName, String rawPath) {
        String normalized = validateRepositoryPath(rawPath);
        String lowerPath = normalized.toLowerCase(Locale.ROOT);

        if (!lowerPath.endsWith(".java") || !lowerPath.matches("(^|.*/)src/main/java/.+\\.java$")) {
            throw new InvalidRequestException("Source files must be Java files under a src/main/java directory");
        }

        String normalizedFileName = validateFileName(fileName);
        String pathFileName = Path.of(normalized).getFileName().toString();
        if (!pathFileName.equals(normalizedFileName)) {
            throw new InvalidRequestException("File name must match the final segment of the file path");
        }

        return normalized;
    }

    public String validateRepositoryPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new InvalidRequestException("Repository path is required");
        }
        if (rawPath.indexOf('\0') >= 0 || rawPath.contains("\\") || rawPath.startsWith("/")
                || WINDOWS_DRIVE.matcher(rawPath).matches()) {
            throw new InvalidRequestException("Repository path must be a relative POSIX path");
        }
        for (String segment : rawPath.split("/")) {
            if (segment.equals("..")) {
                throw new InvalidRequestException("Repository path must not contain parent-directory segments");
            }
        }

        try {
            Path path = Path.of(rawPath).normalize();
            String normalized = path.toString().replace(path.getFileSystem().getSeparator(), "/");
            if (normalized.isBlank() || normalized.equals(".") || normalized.equals("..")
                    || normalized.startsWith("../") || normalized.startsWith(".git/")
                    || normalized.equals(".git")) {
                throw new InvalidRequestException("Repository path escapes or targets a protected directory");
            }
            return normalized;
        } catch (InvalidPathException ex) {
            throw new InvalidRequestException("Repository path is invalid");
        }
    }

    public String validateGeneratedTestClass(String className) {
        if (className == null || className.length() > 255 || !JAVA_CLASS_NAME.matcher(className).matches()) {
            throw new InvalidRequestException("Generated test class must be a valid fully qualified Java class name");
        }
        return className;
    }

    public boolean isSensitiveOrExcluded(String rawPath) {
        String normalized;
        try {
            normalized = validateRepositoryPath(rawPath).toLowerCase(Locale.ROOT);
        } catch (InvalidRequestException ex) {
            return true;
        }

        String name = Path.of(normalized).getFileName().toString();
        return SENSITIVE_FILE_NAMES.contains(name)
                || normalized.startsWith("node_modules/") || normalized.contains("/node_modules/")
                || normalized.startsWith("target/") || normalized.contains("/target/")
                || normalized.startsWith("build/") || normalized.contains("/build/")
                || normalized.startsWith("dist/") || normalized.contains("/dist/")
                || normalized.startsWith("vendor/") || normalized.contains("/vendor/")
                || normalized.startsWith(".idea/") || normalized.startsWith(".vscode/")
                || name.endsWith(".pem") || name.endsWith(".key") || name.endsWith(".p12")
                || name.endsWith(".jks") || name.startsWith(".env.");
    }

    private String validateFileName(String fileName) {
        if (fileName == null || fileName.isBlank() || fileName.length() > 255
                || fileName.contains("/") || fileName.contains("\\") || fileName.indexOf('\0') >= 0) {
            throw new InvalidRequestException("File name is invalid");
        }
        return fileName;
    }
}

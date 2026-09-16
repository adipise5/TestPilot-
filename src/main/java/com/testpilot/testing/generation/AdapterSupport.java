package com.testpilot.testing.generation;

import com.testpilot.ai.dto.TestGenerationResponse;
import com.testpilot.common.exception.InvalidRequestException;
import com.testpilot.testing.entity.TestLevel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class AdapterSupport {
    private AdapterSupport() {}

    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }

    static String stem(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).replaceFirst("\\.[^.]+$", "");
        name = name.replaceAll("[^A-Za-z0-9_]", "_");
        return name.substring(0, Math.min(48, name.length()));
    }

    static String suffix(SourceInput source, TestLevel level) {
        return level.name().toLowerCase(Locale.ROOT) + "_" + hash(source.path()).substring(0, 16);
    }

    static TestPlanItem item(SourceInput source, TestLevel level, String language, String framework,
                             String name, String output, String snapshot, boolean applicable) {
        String objective = switch (level) {
            case UNIT -> "Isolate behavior of this source; cover normal, boundary, invalid-input and exception cases using test doubles.";
            case MODULE -> "Exercise real collaborators within one module; stub infrastructure and do not boot the whole application.";
            case INTEGRATION -> "Exercise an identified framework/dependency boundary with controlled local fixtures; never contact production services.";
        };
        if (!applicable) objective = "No " + level.name().toLowerCase(Locale.ROOT) + " boundary detected; not planned for generation. " + objective;
        return new TestPlanItem(hash("adapters-v1\0" + snapshot + "\0" + source.path() + "\0" + level + "\0" + framework),
                source.path(), language, framework, level, name, output, applicable, objective);
    }

    static boolean applicable(SourceInput source, TestLevel level, List<SourceInput> context) {
        if (level == TestLevel.UNIT) return true;
        String code = source.content();
        if (level == TestLevel.MODULE) {
            String directory = source.path().substring(0, source.path().lastIndexOf('/') + 1);
            return context.stream().anyMatch(other -> !other.path().equals(source.path())
                    && other.path().substring(0, other.path().lastIndexOf('/') + 1).equals(directory)
                    && family(source.path()).equals(family(other.path())))
                    || Pattern.compile("(?m)^\\s*(import |from |const .*require\\()").matcher(code).find();
        }
        return Pattern.compile("(?i)(springframework|JpaRepository|DataSource|sqlalchemy|sqlite3|django|fastapi|flask|express|mongoose|prisma|requests\\.|fetch\\(|HttpClient|WebClient)").matcher(code).find();
    }

    private static String family(String path) {
        if (path.endsWith(".java")) return "java";
        if (path.endsWith(".py")) return "python";
        if (path.matches(".*\\.([cm]?js|jsx|ts|tsx)$")) return "javascript";
        return "unsupported";
    }

    static void common(TestPlanItem plan, TestGenerationResponse response, boolean mock) {
        if (response == null || !plan.testName().equals(response.testClass())) fail("Generated test identity does not match the server-owned plan");
        String code = response.fullTestCode();
        if (code == null || code.isBlank() || code.length() > 200_000 || code.indexOf('\0') >= 0 || code.contains("```")) fail("Empty, oversized or fenced generated code");
        if (response.explanation() == null || response.explanation().length() > 20_000
                || response.tests() == null || response.tests().isEmpty() || response.tests().size() > 100) fail("Invalid generated test metadata");
        var names = new java.util.HashSet<String>();
        response.tests().forEach(test -> {
            if (test == null || test.name() == null || !test.name().matches("[A-Za-z_][A-Za-z0-9_]{0,119}")
                    || test.code() == null || test.code().length() > 20_000
                    || !names.add(test.name()) || !code.contains(test.name())) fail("Missing, duplicate or invalid test case metadata");
        });
        if (!mock && Pattern.compile("(?i)(assertTrue\\(\\s*true|assert\\s+True\\b|expect\\(\\s*true\\s*\\)|pytest\\.(?:skip|mark\\.skip)|@Disabled|(?:test|it|describe)\\.(?:skip|todo)\\s*\\(|TODO|NotImplementedError)").matcher(code).find()) {
            fail("Placeholder, skipped or vacuous tests are not accepted from a real provider");
        }
        if (Pattern.compile("(?i)(https?://|child_process|subprocess|os\\.(?:system|popen)|process\\.exit|Runtime\\.getRuntime|ProcessBuilder|\\beval\\s*\\(|\\bexec\\s*\\()").matcher(code).find()) fail("Generated code uses a disallowed process, dynamic execution or network construct");
    }

    static void require(boolean condition, String reason) { if (!condition) fail(reason); }
    static void fail(String reason) { throw new InvalidRequestException("Generated-test validation: " + reason); }
}

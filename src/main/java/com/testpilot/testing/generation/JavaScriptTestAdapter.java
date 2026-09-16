package com.testpilot.testing.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.ai.dto.TestGenerationResponse;
import com.testpilot.testing.entity.TestLevel;
import org.springframework.stereotype.Component;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class JavaScriptTestAdapter implements LanguageTestAdapter {
    public boolean supports(String path) { return path.matches(".*\\.(?:[cm]?js|jsx|ts|tsx)$") && !path.endsWith(".d.ts"); }

    public TestPlanItem plan(SourceInput source, TestLevel level, List<SourceInput> context, String snapshot) {
        boolean typescript = source.path().matches(".*\\.tsx?$");
        String name = "tp_" + AdapterSupport.stem(source.path()) + "_" + AdapterSupport.suffix(source, level);
        String directory = source.path().substring(0, source.path().lastIndexOf('/') + 1);
        return AdapterSupport.item(source, level, typescript ? "TypeScript" : "JavaScript", framework(source, context), name,
                directory + "__testpilot__/" + name + ".test." + (typescript ? "ts" : "js"), snapshot,
                AdapterSupport.applicable(source, level, context));
    }

    private String framework(SourceInput source, List<SourceInput> context) {
        var manifest = context.stream().filter(s -> s.path().equals("package.json") || s.path().endsWith("/package.json"))
                .filter(s -> source.path().startsWith(s.path().substring(0, s.path().length() - "package.json".length())))
                .max(Comparator.comparingInt(s -> s.path().length()));
        if (manifest.isPresent()) {
            try {
                var json = new ObjectMapper().readTree(manifest.get().content());
                if (json != null) {
                    if (json.path("devDependencies").has("vitest") || json.path("dependencies").has("vitest")) return "Vitest";
                    if (json.path("devDependencies").has("jest") || json.path("dependencies").has("jest")) return "Jest";
                }
            } catch (java.io.IOException ignored) { /* Default is a proposal, not detected installation. */ }
        }
        return "Vitest (proposed)";
    }

    public String instructions(TestPlanItem plan) {
        String frameworkImport = plan.framework().equals("Jest") ? "@jest/globals" : "vitest";
        return "Use " + plan.framework() + " with explicit imports of describe, test and expect from '" + frameworkImport
                + "'. Use describe('" + plan.testName() + " [" + plan.level().name().toLowerCase(Locale.ROOT) + "]', ...). "
                + "Use real assertions and production imports relative to the assigned outputPath. Mock collaborators with "
                + (plan.framework().equals("Jest") ? "jest.mock/jest.fn" : "vi.mock/vi.fn")
                + ". No Playwright, Cypress, browser/system testing, dependency installation, network calls, or shell commands. "
                + "TypeScript output must use valid TS imports/types; do not claim the transpiler or framework is installed.";
    }

    public List<String> validate(TestPlanItem plan, TestGenerationResponse response, boolean mock) {
        AdapterSupport.common(plan, response, mock);
        String code = response.fullTestCode();
        String module = plan.framework().equals("Jest") ? "@jest/globals" : "vitest";
        AdapterSupport.require(Pattern.compile("from\\s*['\"]" + Pattern.quote(module) + "['\"]").matcher(code).find(), "Wrong JS test framework import");
        AdapterSupport.require(code.contains(plan.testName() + " [" + plan.level().name().toLowerCase(Locale.ROOT) + "]"), "Missing planned suite name/level");
        AdapterSupport.require(Pattern.compile("\\b(?:test|it)(?:\\.skip)?\\s*\\(").matcher(code).find() && code.contains("expect("), "Missing test and assertion");
        AdapterSupport.require(!Pattern.compile("(fetch\\s*\\(|axios|node:(?:net|http|https|child_process)|playwright|cypress|puppeteer)", Pattern.CASE_INSENSITIVE).matcher(code).find(), "Unsupported network/system API");
        return List.of("js-test-structure", "framework-import", "planned-suite-and-level", "restricted-api-scan");
    }
}

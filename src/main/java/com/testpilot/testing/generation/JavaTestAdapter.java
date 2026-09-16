package com.testpilot.testing.generation;

import com.testpilot.ai.dto.TestGenerationResponse;
import com.testpilot.testing.entity.TestLevel;
import com.testpilot.testing.validation.GeneratedTestPolicyValidator;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class JavaTestAdapter implements LanguageTestAdapter {
    public boolean supports(String path) { return path.endsWith(".java"); }

    public TestPlanItem plan(SourceInput source, TestLevel level, List<SourceInput> context, String snapshot) {
        var match = Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_][\\w.]*)\\s*;").matcher(source.content());
        String pkg = match.find() ? match.group(1) : "";
        String simple = "Tp" + AdapterSupport.stem(source.path()) + "_" + AdapterSupport.suffix(source, level) + "Test";
        String name = pkg.isBlank() ? simple : pkg + "." + simple;
        return AdapterSupport.item(source, level, "Java", "JUnit 5 / Mockito", name,
                "src/test/java/" + name.replace('.', '/') + ".java", snapshot, AdapterSupport.applicable(source, level, context));
    }

    public String instructions(TestPlanItem plan) {
        return "Use JUnit 5 and Mockito, real assertions against the supplied production code, package matching the assigned fully qualified testName, "
                + "and @Tag(\"" + plan.level().name().toLowerCase(java.util.Locale.ROOT) + "\"). Generate exactly one complete Java class. "
                + "Use Spring test slices only for integration when the source justifies them. No full application or infrastructure in unit/module tests.";
    }

    public List<String> validate(TestPlanItem plan, TestGenerationResponse response, boolean mock) {
        AdapterSupport.common(plan, response, mock);
        String code = response.fullTestCode();
        int dot = plan.testName().lastIndexOf('.');
        String pkg = dot < 0 ? "" : plan.testName().substring(0, dot);
        AdapterSupport.require(Pattern.compile("\\bclass\\s+" + Pattern.quote(plan.testName().substring(dot + 1)) + "\\b").matcher(code).find(), "Expected Java class not declared");
        var declared = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(code);
        AdapterSupport.require(pkg.equals(declared.find() ? declared.group(1) : ""), "Java package differs from the planned output");
        AdapterSupport.require(code.contains("@Tag(\"" + plan.level().name().toLowerCase(java.util.Locale.ROOT) + "\")"), "Missing planned test-level tag");
        AdapterSupport.require(Pattern.compile("\\b(assert\\w+|verify|fail)\\s*\\(").matcher(code).find(), "Java tests need assertions");
        return new GeneratedTestPolicyValidator().validate(plan.testName(), code, plan.level());
    }
}

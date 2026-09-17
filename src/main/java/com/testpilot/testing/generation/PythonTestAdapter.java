package com.testpilot.testing.generation;

import com.testpilot.ai.dto.TestGenerationResponse;
import com.testpilot.testing.entity.TestLevel;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class PythonTestAdapter implements LanguageTestAdapter {
    public boolean supports(String path) { return path.endsWith(".py"); }

    public TestPlanItem plan(SourceInput source, TestLevel level, List<SourceInput> context, String snapshot) {
        String name = "test_tp_" + AdapterSupport.stem(source.path()).toLowerCase(Locale.ROOT) + "_" + AdapterSupport.suffix(source, level);
        return AdapterSupport.item(source, level, "Python", "pytest", name, "tests/testpilot/" + name + ".py",
                snapshot, AdapterSupport.applicable(source, level, context));
    }

    public String instructions(TestPlanItem plan) {
        return "Use pytest, import pytest, module-level pytestmark = pytest.mark." + plan.level().name().toLowerCase(Locale.ROOT)
                + ", def test_* functions, pytest.raises/parametrize and unittest.mock or monkeypatch for collaborators. "
                + "Import actual production functions using the provided repository layout. Do not mutate sys.path, install dependencies, "
                + "or invent APIs. Integration uses temporary local fixtures; unit/module must not open network or databases. "
                + "The offline worker registers unit, module and integration markers.";
    }

    public List<String> validate(TestPlanItem plan, TestGenerationResponse response, boolean mock) {
        AdapterSupport.common(plan, response, mock);
        String code = response.fullTestCode();
        AdapterSupport.require(Pattern.compile("(?m)^import pytest\\s*$").matcher(code).find(), "Missing pytest import");
        AdapterSupport.require(Pattern.compile("(?m)^(?:async )?def test_[A-Za-z0-9_]+\\s*\\(").matcher(code).find(), "Missing pytest test function");
        AdapterSupport.require(code.contains("pytest.mark." + plan.level().name().toLowerCase(Locale.ROOT)), "Missing pytest level marker");
        AdapterSupport.require(Pattern.compile("\\bassert\\s|pytest\\.raises\\s*\\(").matcher(code).find(), "Python tests need assertions");
        AdapterSupport.require(!Pattern.compile("(?m)^\\s*(?:from|import)\\s+(?:subprocess|socket|requests|urllib|httpx)\\b").matcher(code).find(), "Uncontrolled Python process/network import");
        if (plan.level() != TestLevel.INTEGRATION) AdapterSupport.require(!Pattern.compile("\\b(sqlite3|sqlalchemy|testcontainers|django\\.test)\\b").matcher(code).find(), "Infrastructure outside integration level");
        return List.of("pytest-structure", "planned-identity", "level-marker", "restricted-api-scan");
    }
}

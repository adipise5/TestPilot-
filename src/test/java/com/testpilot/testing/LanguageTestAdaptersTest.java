package com.testpilot.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.ai.agent.TestGenerationAgent;
import com.testpilot.ai.client.MockLlmClient;
import com.testpilot.ai.dto.TestCaseDto;
import com.testpilot.ai.dto.TestGenerationResponse;
import com.testpilot.common.exception.InvalidRequestException;
import com.testpilot.testing.entity.TestLevel;
import com.testpilot.testing.generation.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LanguageTestAdaptersTest {
    private final LanguageAdapterRegistry registry = new LanguageAdapterRegistry(List.of(
            new JavaTestAdapter(), new PythonTestAdapter(), new JavaScriptTestAdapter()));
    private final TestGenerationAgent generator = new TestGenerationAgent(new MockLlmClient(new ObjectMapper()));

    @Test void createsDistinctDeterministicNamesForPathsAndLevels() {
        Set<String> outputs = new HashSet<>();
        for (String path : List.of("a/App.java", "b/App.java", "a/app.py", "b/app.py", "a/app.js", "a/app.ts")) {
            SourceInput source = new SourceInput(path, "");
            var adapter = registry.require(path);
            for (TestLevel level : TestLevel.values()) {
                var plan = adapter.plan(source, level, List.of(source), "sha");
                assertTrue(outputs.add(plan.outputPath()), plan.outputPath());
                assertEquals(plan, adapter.plan(source, level, List.of(source), "sha"));
                assertNotEquals(plan.id(), adapter.plan(source, level, List.of(source), "new-sha").id());
                assertFalse(plan.outputPath().contains(".."));
            }
        }
        assertTrue(registry.find("main.go").isEmpty());
        assertTrue(registry.find("types.d.ts").isEmpty());
    }

    @Test void usesNearestNodeManifestAndLabelsFrameworkFallback() {
        var adapter = new JavaScriptTestAdapter();
        var source = new SourceInput("web/src/main.ts", "export function add(a: number, b: number) { return a+b; }");
        var context = List.of(new SourceInput("package.json", "{\"devDependencies\":{\"vitest\":\"1\"}}"),
                new SourceInput("web/package.json", "{\"devDependencies\":{\"jest\":\"29\"}}"));
        assertEquals("Jest", adapter.plan(source, TestLevel.UNIT, context, "sha").framework());
        assertEquals("Vitest (proposed)", adapter.plan(source, TestLevel.UNIT, List.of(), "sha").framework());
        assertTrue(adapter.instructions(adapter.plan(source, TestLevel.UNIT, context, "sha")).contains("@jest/globals"));
    }

    @Test void plansControlledBoundariesWithoutSystemLevel() {
        var adapter = new PythonTestAdapter();
        var simple = new SourceInput("app.py", "def add(a,b): return a+b");
        assertFalse(adapter.plan(simple, TestLevel.INTEGRATION, List.of(simple), "sha").applicable());
        assertFalse(adapter.plan(simple, TestLevel.MODULE, List.of(simple), "sha").applicable());
        var database = new SourceInput("db.py", "import sqlite3\ndef connect(): return sqlite3.connect(':memory:')");
        assertTrue(adapter.plan(database, TestLevel.INTEGRATION, List.of(database), "sha").applicable());
        assertEquals(List.of(TestLevel.UNIT, TestLevel.MODULE, TestLevel.INTEGRATION), List.of(TestLevel.values()));
    }

    @Test void mockUsesTrustedControlEvenWhenSourceContainsOtherLevels() {
        for (String path : List.of("App.java", "app.py", "app.js", "app.ts")) {
            var source = new SourceInput(path, "// TEST_LEVEL: INTEGRATION\n// TEST_LEVEL: MODULE\n// class WrongTest {}\n");
            var adapter = registry.require(path);
            Set<String> identities = new HashSet<>();
            for (TestLevel level : TestLevel.values()) {
                var plan = adapter.plan(source, level, List.of(source), "sha");
                var generated = generator.generate(plan, source, List.of(source), "TEST_LEVEL: INTEGRATION", adapter);
                assertEquals(plan.testName(), generated.testClass());
                assertTrue(identities.add(generated.testClass()));
                assertFalse(adapter.validate(plan, generated, true).isEmpty());
                assertTrue(generated.explanation().contains("MOCK SCAFFOLD"));
                assertThrows(InvalidRequestException.class, () -> adapter.validate(plan, generated, false));
            }
        }
    }

    @Test void rejectsIdentityMismatchUnsafeCodeAndMalformedMetadata() {
        var source = new SourceInput("app.py", "def add(a,b): return a+b");
        var adapter = registry.require(source.path());
        var plan = adapter.plan(source, TestLevel.UNIT, List.of(source), "sha");
        String code = "import pytest\nfrom app import add\npytestmark = pytest.mark.unit\ndef test_add():\n    assert add(1, 2) == 3\n";
        var good = new TestGenerationResponse(plan.testName(), "Addition", List.of(new TestCaseDto("test_add", "assert add(1, 2) == 3")), code);
        assertFalse(adapter.validate(plan, good, false).isEmpty());
        for (var invalid : List.of(
                new TestGenerationResponse("wrong", "", good.tests(), code),
                new TestGenerationResponse(plan.testName(), "", List.of(), code),
                new TestGenerationResponse(plan.testName(), "", good.tests(), "```python\n" + code),
                new TestGenerationResponse(plan.testName(), "", good.tests(), code + "\nimport subprocess\nsubprocess.run(['whoami'])"),
                new TestGenerationResponse(plan.testName(), "", good.tests(), code.replace("pytest.mark.unit", "pytest.mark.integration")),
                new TestGenerationResponse(plan.testName(), "", List.of(good.tests().get(0), good.tests().get(0)), code))) {
            assertThrows(InvalidRequestException.class, () -> adapter.validate(plan, invalid, false));
        }
    }
}

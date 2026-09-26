package com.testpilot.testing.workflow.service;

import com.testpilot.project.entity.CodeFile;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowSourceSelectorTest {
    @Test
    void choosesBehavioralSourceBeforeApplicationEntrypointAndRetainsEveryFile() {
        var app = file("src/main/java/example/App.java", "@SpringBootApplication public class App { public static void main(String[] args) {} }");
        var api = file("src/main/java/example/Api.java", "public interface Api {}");
        var validator = file("src/main/java/example/validation/PricePolicy.java", "public class PricePolicy { public int price(int n) { return n; } }");
        var ordered = WorkflowSourceSelector.order(List.of(app, api, validator));
        assertEquals(List.of(validator, api, app), ordered);
    }

    @Test
    void passiveComponentDoesNotRequireApplicationContext() {
        assertFalse(WorkflowSourceSelector.needsFrameworkWiring(
                "import org.springframework.stereotype.Component; @Component public class PathPolicy {}"));
        assertTrue(WorkflowSourceSelector.needsFrameworkWiring(
                "@RestController public class PriceController {}"));
    }

    private CodeFile file(String path, String content) {
        return new CodeFile(1L, path.substring(path.lastIndexOf('/') + 1), path, content);
    }
}

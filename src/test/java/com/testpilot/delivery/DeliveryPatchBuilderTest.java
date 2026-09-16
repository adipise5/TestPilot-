package com.testpilot.delivery;

import com.testpilot.common.exception.InvalidRequestException;
import com.testpilot.common.validation.RepositoryPathPolicy;
import com.testpilot.delivery.service.DeliveryPatchBuilder;
import com.testpilot.delivery.service.GitBranchPolicy;
import com.testpilot.testing.entity.GeneratedTest;
import com.testpilot.testing.entity.TestLevel;
import com.testpilot.testing.validation.GeneratedTestPolicyValidator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryPatchBuilderTest {

    private final DeliveryPatchBuilder builder = new DeliveryPatchBuilder(
            new RepositoryPathPolicy(), new GeneratedTestPolicyValidator());

    @Test
    void createsDeterministicNewFilePatch() {
        GeneratedTest test = generatedTest();

        var first = builder.prepare(List.of(test), Set.of());
        var second = builder.prepare(List.of(test), Set.of());

        assertEquals(first.patchSha256(), second.patchSha256());
        assertEquals(64, first.patchSha256().length());
        assertEquals("src/test/java/com/example/CalculatorTest.java", first.changes().get(0).path());
        assertTrue(first.patchText().contains("--- /dev/null"));
        assertTrue(first.patchText().contains("@@ -0,0 +1,6 @@"));
        assertTrue(first.patchText().contains("+class CalculatorTest"));
    }

    @Test
    void refusesToOverwriteAnExistingRepositoryTest() {
        assertThrows(InvalidRequestException.class, () -> builder.prepare(
                List.of(generatedTest()), Set.of("src/test/java/com/example/CalculatorTest.java")));
    }

    @Test
    void deliveryBranchCannotBeTheDefaultBranch() {
        GitBranchPolicy policy = new GitBranchPolicy();
        assertEquals("testpilot/run-1-deadbeef", policy.requireDeliveryBranch(
                "testpilot/run-1-deadbeef", "main"));
        assertThrows(InvalidRequestException.class, () -> policy.requireDeliveryBranch("main", "main"));
        assertThrows(InvalidRequestException.class, () -> policy.requireDeliveryBranch("feature/test", "main"));
    }

    private GeneratedTest generatedTest() {
        return new GeneratedTest(1L, "Calculator.java", "com.example.CalculatorTest", """
                package com.example;
                import org.junit.jupiter.api.Test;
                class CalculatorTest {
                    @Test
                    void adds() { org.junit.jupiter.api.Assertions.assertEquals(2, 1 + 1); }
                }
                """, TestLevel.UNIT);
    }
}

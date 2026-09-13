package com.testpilot.testing;

import com.testpilot.common.exception.InvalidRequestException;
import com.testpilot.testing.entity.TestLevel;
import com.testpilot.testing.validation.GeneratedTestPolicyValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GeneratedTestPolicyValidatorTest {

    private final GeneratedTestPolicyValidator validator = new GeneratedTestPolicyValidator();

    @Test
    void enforcesDifferentLevelBoundaries() {
        String unit = "import org.junit.jupiter.api.Test; class PriceTest { @Test void calculates() {} }";
        assertTrue(validator.validate("PriceTest", unit, TestLevel.UNIT).contains("unit-isolation"));

        String integration = "import org.junit.jupiter.api.Test; class PriceIntegrationTest { @Tag(\"integration\") "
                + "@Test void persists() {} }";
        assertTrue(validator.validate("PriceIntegrationTest", integration, TestLevel.INTEGRATION)
                .contains("controlled-integration-boundary"));

        assertThrows(InvalidRequestException.class,
                () -> validator.validate("PriceTest", unit + " new ProcessBuilder(\"sh\");", TestLevel.UNIT));
        assertThrows(InvalidRequestException.class,
                () -> validator.validate("PriceTest", unit + " @SpringBootTest", TestLevel.UNIT));
        assertThrows(InvalidRequestException.class,
                () -> validator.validate("PriceIntegrationTest", unit, TestLevel.INTEGRATION));
    }
}

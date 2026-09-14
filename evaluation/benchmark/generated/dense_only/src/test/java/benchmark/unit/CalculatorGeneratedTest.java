package benchmark.unit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CalculatorGeneratedTest {
    @Test
    @Tag("unit")
    void dividesTypicalValues() {
        assertEquals(4, new Calculator().divide(8, 2));
    }
}

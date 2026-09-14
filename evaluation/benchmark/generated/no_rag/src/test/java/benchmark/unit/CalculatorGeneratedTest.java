package benchmark.unit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CalculatorGeneratedTest {
    @Test
    @Tag("unit")
    void addsTwoTypicalValues() {
        assertEquals(7, new Calculator().add(3, 4));
    }
}

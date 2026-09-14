package benchmark.unit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class CalculatorGeneratedTest {
    @Test
    @Tag("unit")
    void rejectsDivisionByZero() {
        Calculator calculator = new Calculator();
        assertThrows(ArithmeticException.class, () -> calculator.divide(8, 0));
    }
}

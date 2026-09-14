package benchmark.unit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CalculatorGeneratedTest {
    @Test
    @Tag("unit")
    void coversDivisionContractAndNegativeInputs() {
        Calculator calculator = new Calculator();
        assertEquals(-4, calculator.divide(-8, 2));
        assertThrows(ArithmeticException.class, () -> calculator.divide(8, 0));
    }
}

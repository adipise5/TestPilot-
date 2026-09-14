package benchmark.unit;

public class Calculator {
    public int add(int left, int right) {
        return Math.addExact(left, right);
    }

    public int divide(int numerator, int denominator) {
        if (denominator == 0) {
            throw new ArithmeticException("division by zero");
        }
        return numerator / denominator;
    }
}

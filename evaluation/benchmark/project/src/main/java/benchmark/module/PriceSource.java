package benchmark.module;

import java.util.List;

@FunctionalInterface
public interface PriceSource {
    List<Integer> currentPrices();
}

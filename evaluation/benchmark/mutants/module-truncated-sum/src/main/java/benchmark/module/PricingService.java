package benchmark.module;

import java.util.List;

public class PricingService {
    private final PriceSource priceSource;

    public PricingService(PriceSource priceSource) {
        this.priceSource = priceSource;
    }

    public int total() {
        List<Integer> prices = priceSource.currentPrices();
        return prices.isEmpty() ? 0 : prices.get(0);
    }
}

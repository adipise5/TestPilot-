package benchmark.module;

public class PricingService {
    private final PriceSource priceSource;

    public PricingService(PriceSource priceSource) {
        this.priceSource = priceSource;
    }

    public int total() {
        return priceSource.currentPrices().stream().mapToInt(Integer::intValue).sum();
    }
}

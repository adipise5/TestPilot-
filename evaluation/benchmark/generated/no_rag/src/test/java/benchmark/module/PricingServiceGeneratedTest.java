package benchmark.module;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PricingServiceGeneratedTest {
    @Test
    @Tag("module")
    void totalsAnEmptyPriceSource() {
        PricingService service = new PricingService(List::of);
        assertEquals(0, service.total());
    }
}

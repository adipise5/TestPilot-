package benchmark.module;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PricingServiceGeneratedTest {
    @Test
    @Tag("module")
    void aggregatesEveryCollaboratorValue() {
        PricingService service = new PricingService(() -> List.of(10, 15));
        assertEquals(25, service.total());
    }
}

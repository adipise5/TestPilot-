package com.testpilot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("h2")
class TestPilotApplicationTests {

    @Test
    void contextLoads() {
        // Verifies Spring ApplicationContext starts successfully
    }
}

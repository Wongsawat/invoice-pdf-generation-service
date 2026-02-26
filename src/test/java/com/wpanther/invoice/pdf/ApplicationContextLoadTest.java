package com.wpanther.invoice.pdf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Verifies that the full Spring application context loads without error.
 *
 * <p>All existing tests use Mockito-only or @DataJpaTest slices and never start the
 * full context. This test catches wiring failures (missing required property, wrong
 * type, circular dependency) that would otherwise only surface at deployment time.</p>
 *
 * <p>{@code camel.main.auto-startup=false} prevents Camel routes from starting Kafka
 * consumers so the test does not require a running Kafka broker.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "camel.main.auto-startup=false")
@DisplayName("Application Context Load Test")
class ApplicationContextLoadTest {

    @Test
    @DisplayName("Spring context loads — all beans correctly wired")
    void contextLoads() {
        // If any bean fails to wire (missing required property, wrong type,
        // circular dependency), Spring throws before reaching this line.
    }
}

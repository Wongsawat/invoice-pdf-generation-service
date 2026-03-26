package com.wpanther.invoice.pdf.infrastructure.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Integration tests for FontHealthCheck.
 * <p>
 * Note: These tests require the actual font files to be present in src/main/resources/fonts/
 * If fonts are missing and fail-on-error is true, the application will fail to start.
 */
@SpringBootTest(classes = FontHealthCheck.class,
        properties = {
                "spring.main.application-context=application"
        })
@TestPropertySource(properties = {
        "app.fonts.health-check.enabled=true",
        "app.fonts.health-check.fail-on-error=false"
})
@DisplayName("FontHealthCheck Integration Tests")
class FontHealthCheckTest {

    @Autowired(required = false)
    private FontHealthCheck fontHealthCheck;

    @Test
    @DisplayName("FontHealthCheck bean should be created when enabled")
    void testBeanCreation() {
        assertThat(fontHealthCheck).isNotNull();
    }

    @Test
    @DisplayName("checkFontsAtStartup() should not throw when fail-on-error is false")
    void testCheckFontsDoesNotThrowWhenFailOnErrorIsFalse() {
        assertThatCode(() -> fontHealthCheck.checkFontsAtStartup())
                .doesNotThrowAnyException();
    }
}

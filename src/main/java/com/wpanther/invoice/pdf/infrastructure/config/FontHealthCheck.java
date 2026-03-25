package com.wpanther.invoice.pdf.infrastructure.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Startup health check for required Thai font files.
 * <p>
 * Validates that all fonts referenced in fop.xconf are present in the classpath.
 * Fails fast at startup if fonts are missing, preventing runtime PDF generation failures.
 * <p>
 * Enabled via configuration: {@code app.fonts.health-check.enabled=true}
 * <p>
 * Required fonts (from fop/fop.xconf):
 * <ul>
 *   <li>NotoSansThaiLooped-Regular.ttf, NotoSansThaiLooped-Bold.ttf</li>
 * </ul>
 * <p>
 * Font sources:
 * <ul>
 *   <li>Noto Sans Thai Looped: https://fonts.google.com/noto/specimen/Noto+Sans+Thai+Looped</li>
 * </ul>
 * <p>
 * Note: TH Sarabun New fonts are not included in this repository. If your fop.xconf
 * configuration requires them, download from: https://www.f0nt.com/release/th-sarabun-new/
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "app.fonts.health-check.enabled", havingValue = "true", matchIfMissing = true)
public class FontHealthCheck {

    /**
     * Required Thai font files for PDF generation.
     * These must match the fonts configured in fop/fop.xconf.
     *
     * Note: Only Noto Sans Thai Looped fonts are included in this repository.
     * TH Sarabun New fonts need to be downloaded separately if required by fop.xconf.
     * See: src/main/resources/fonts/README.md
     */
    private static final String[] REQUIRED_FONTS = {
            "fonts/NotoSansThaiLooped-Regular.ttf",
            "fonts/NotoSansThaiLooped-Bold.ttf"
    };

    @Value("${app.fonts.health-check.fail-on-error:true}")
    private boolean failOnError;

    /**
     * Validate all required fonts are present at application startup.
     * <p>
     * Runs after application context is fully initialized but before
     * the application accepts traffic. Logs warnings or throws exception
     * based on configuration.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void checkFontsAtStartup() {
        log.info("Performing font health check...");

        List<String> missingFonts = new ArrayList<>();
        List<String> presentFonts = new ArrayList<>();

        for (String fontPath : REQUIRED_FONTS) {
            ClassPathResource resource = new ClassPathResource(fontPath);
            if (resource.exists()) {
                presentFonts.add(fontPath);
                log.debug("Font found: {}", fontPath);
            } else {
                missingFonts.add(fontPath);
                log.warn("Required font missing: {}", fontPath);
            }
        }

        if (missingFonts.isEmpty()) {
            log.info("Font health check passed: All {} required fonts are present", presentFonts.size());
            return;
        }

        String errorMessage = buildErrorMessage(missingFonts);

        if (failOnError) {
            log.error("Font health check failed: {}", errorMessage);
            throw new IllegalStateException(errorMessage);
        } else {
            log.warn("Font health check failed but continuing: {}", errorMessage);
            log.warn("PDF generation may fail at runtime without required fonts!");
        }
    }

    private String buildErrorMessage(List<String> missingFonts) {
        return String.format(
                "Missing %d of %d required Thai fonts: %s. " +
                "Place fonts in src/main/resources/fonts/ or disable check with app.fonts.health-check.enabled=false. " +
                "Download fonts from: Noto Sans Thai Looped (https://fonts.google.com/noto/specimen/Noto+Sans+Thai+Looped)",
                missingFonts.size(),
                REQUIRED_FONTS.length,
                missingFonts
        );
    }
}

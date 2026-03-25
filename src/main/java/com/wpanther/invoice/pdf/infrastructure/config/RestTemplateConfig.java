package com.wpanther.invoice.pdf.infrastructure.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for RestTemplate with circuit breaker event logging.
 * <p>
 * The RestTemplate bean itself is configured in {@link OutboxConfig}
 * with connect and read timeouts to prevent thread exhaustion when
 * the signed XML service is unresponsive.
 * <p>
 * Circuit breakers are configured via application.yml under resilience4j.circuitbreaker:
 * - signedXmlFetch: for signed XML fetch operations
 * - minio: for MinIO S3 operations
 * <p>
 * This class sets up event logging for circuit breaker state transitions
 * to help debug production issues when external services degrade.
 */
@Configuration
@Slf4j
public class RestTemplateConfig {

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public RestTemplateConfig(CircuitBreakerRegistry circuitBreakerRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    /**
     * Set up circuit breaker event logging after Spring context initialization.
     * <p>
     * Logs state transitions (CLOSED → OPEN → HALF_OPEN → CLOSED) for both
     * circuit breakers, providing visibility into when external services are
     * degrading and when the system begins accepting traffic again.
     */
    @PostConstruct
    public void setupCircuitBreakerLogging() {
        CircuitBreaker signedXmlFetch = circuitBreakerRegistry.circuitBreaker("signedXmlFetch");
        CircuitBreaker minio = circuitBreakerRegistry.circuitBreaker("minio");

        signedXmlFetch.getEventPublisher()
                .onStateTransition(event ->
                        log.info("Circuit breaker 'signedXmlFetch' state transition: {}",
                                event.getStateTransition()));

        minio.getEventPublisher()
                .onStateTransition(event ->
                        log.info("Circuit breaker 'minio' state transition: {}",
                                event.getStateTransition()));

        log.info("Circuit breaker event logging configured for 'signedXmlFetch' and 'minio'");
    }
}

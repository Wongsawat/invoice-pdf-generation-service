package com.wpanther.invoice.pdf.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Metrics for PDF generation operations.
 * <p>
 * Tracks key operational metrics including retry exhaustion events, which can
 * indicate upstream service issues when occurring frequently.
 */
@Component
public class PdfGenerationMetrics {

    private static final String RETRY_EXHAUSTED_COUNTER = "pdf.generation.retry.exhausted";
    private static final String TAG_SAGA_ID = "saga_id";
    private static final String TAG_INVOICE_ID = "invoice_id";
    private static final String TAG_INVOICE_NUMBER = "invoice_number";

    private final MeterRegistry meterRegistry;

    public PdfGenerationMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        Counter.builder(RETRY_EXHAUSTED_COUNTER)
                .description("Number of times PDF generation max retries were exceeded")
                .register(meterRegistry);
    }

    /**
     * Record a retry exhaustion event for monitoring.
     *
     * @param sagaId the saga ID for correlation
     * @param invoiceId the invoice ID
     * @param invoiceNumber the invoice number
     */
    public void recordRetryExhausted(String sagaId, String invoiceId, String invoiceNumber) {
        meterRegistry.counter(RETRY_EXHAUSTED_COUNTER,
                TAG_SAGA_ID, sagaId,
                TAG_INVOICE_ID, invoiceId,
                TAG_INVOICE_NUMBER, invoiceNumber)
            .increment();
    }
}

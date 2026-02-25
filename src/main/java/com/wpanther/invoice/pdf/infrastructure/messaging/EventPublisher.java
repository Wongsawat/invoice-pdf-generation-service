package com.wpanther.invoice.pdf.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.invoice.pdf.domain.event.InvoicePdfGeneratedEvent;
import com.wpanther.saga.infrastructure.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Publishes integration events via outbox pattern for reliable delivery.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EventPublisher {

    private static final String AGGREGATE_TYPE = "InvoicePdfDocument";

    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    /**
     * Publish PDF generated event to pdf.generated topic (for Notification Service).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishPdfGenerated(InvoicePdfGeneratedEvent event) {
        Map<String, String> headers = Map.of(
            "documentType", "INVOICE",
            "correlationId", event.getCorrelationId()
        );

        outboxService.saveWithRouting(
                event,
                AGGREGATE_TYPE,
                event.getInvoiceId(),
                "pdf.generated.invoice",
                event.getInvoiceId(),
                toJson(headers)
        );

        log.info("Published InvoicePdfGeneratedEvent to outbox for notification: {}", event.getInvoiceNumber());
    }

    private String toJson(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize headers to JSON", e);
            return null;
        }
    }
}

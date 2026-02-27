package com.wpanther.invoice.pdf.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.invoice.pdf.application.port.out.PdfEventPort;
import com.wpanther.invoice.pdf.domain.event.InvoicePdfGeneratedEvent;
import com.wpanther.saga.infrastructure.outbox.OutboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Publishes integration events via outbox pattern for reliable delivery.
 */
@Component
@Slf4j
public class EventPublisher implements PdfEventPort {

    private static final String AGGREGATE_TYPE = "InvoicePdfDocument";
    private static final String DOCUMENT_TYPE = "INVOICE";

    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;
    private final String pdfGeneratedTopic;

    public EventPublisher(OutboxService outboxService,
                          ObjectMapper objectMapper,
                          @Value("${app.kafka.topics.pdf-generated}") String pdfGeneratedTopic) {
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
        this.pdfGeneratedTopic = pdfGeneratedTopic;
    }

    /**
     * Publish PDF generated event to pdf.generated topic (for Notification Service).
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishPdfGenerated(InvoicePdfGeneratedEvent event) {
        Map<String, String> headers = Map.of(
            "documentType", DOCUMENT_TYPE,
            "correlationId", event.getCorrelationId()
        );

        outboxService.saveWithRouting(
                event,
                AGGREGATE_TYPE,
                event.getInvoiceId(),
                pdfGeneratedTopic,
                event.getInvoiceId(),
                MessagingUtils.toJson(headers, objectMapper)
        );

        log.info("Published InvoicePdfGeneratedEvent to outbox: invoiceNumber={} documentId={} correlationId={}",
                event.getInvoiceNumber(), event.getDocumentId(), event.getCorrelationId());
    }
}

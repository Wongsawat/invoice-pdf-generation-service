package com.wpanther.invoice.pdf.infrastructure.adapter.out.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.invoice.pdf.application.port.out.PdfEventPort;
import com.wpanther.invoice.pdf.domain.constants.PdfGenerationConstants;
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
            "documentType", PdfGenerationConstants.DOCUMENT_TYPE,
            "correlationId", event.getCorrelationId()
        );

        outboxService.saveWithRouting(
                event,
                OutboxConstants.AGGREGATE_TYPE,
                event.getDocumentId(),
                pdfGeneratedTopic,
                event.getDocumentId(),
                MessagingUtils.toJson(headers, objectMapper)
        );

        log.info("Published InvoicePdfGeneratedEvent to outbox: documentNumber={} documentId={} correlationId={}",
                event.getDocumentNumber(), event.getDocumentId(), event.getCorrelationId());
    }
}

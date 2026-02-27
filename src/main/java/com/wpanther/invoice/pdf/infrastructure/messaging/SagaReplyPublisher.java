package com.wpanther.invoice.pdf.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.invoice.pdf.application.port.out.SagaReplyPort;
import com.wpanther.invoice.pdf.domain.event.InvoicePdfReplyEvent;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.saga.infrastructure.outbox.OutboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Publishes saga reply events via outbox pattern.
 * Replies are sent to orchestrator via the configured saga reply topic.
 */
@Component
@Slf4j
public class SagaReplyPublisher implements SagaReplyPort {

    private static final String AGGREGATE_TYPE = "InvoicePdfDocument";

    private final String replyTopic;
    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    public SagaReplyPublisher(@Value("${app.kafka.topics.saga-reply-invoice-pdf}") String replyTopic,
                              OutboxService outboxService,
                              ObjectMapper objectMapper) {
        this.replyTopic = replyTopic;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishSuccess(String sagaId, SagaStep sagaStep, String correlationId, String pdfUrl, long pdfSize) {
        InvoicePdfReplyEvent reply = InvoicePdfReplyEvent.success(sagaId, sagaStep, correlationId, pdfUrl, pdfSize);

        Map<String, String> headers = Map.of(
                "sagaId", sagaId,
                "correlationId", correlationId,
                "status", "SUCCESS"
        );

        outboxService.saveWithRouting(
                reply,
                AGGREGATE_TYPE,
                sagaId,
                replyTopic,
                sagaId,
                MessagingUtils.toJson(headers, objectMapper)
        );

        log.info("Published SUCCESS saga reply for saga {} step {} with pdfUrl={}", sagaId, sagaStep, pdfUrl);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishFailure(String sagaId, SagaStep sagaStep, String correlationId, String errorMessage) {
        InvoicePdfReplyEvent reply = InvoicePdfReplyEvent.failure(sagaId, sagaStep, correlationId, errorMessage);

        Map<String, String> headers = Map.of(
                "sagaId", sagaId,
                "correlationId", correlationId,
                "status", "FAILURE"
        );

        outboxService.saveWithRouting(
                reply,
                AGGREGATE_TYPE,
                sagaId,
                replyTopic,
                sagaId,
                MessagingUtils.toJson(headers, objectMapper)
        );

        log.info("Published FAILURE saga reply for saga {} step {}: {}", sagaId, sagaStep, errorMessage);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishCompensated(String sagaId, SagaStep sagaStep, String correlationId) {
        InvoicePdfReplyEvent reply = InvoicePdfReplyEvent.compensated(sagaId, sagaStep, correlationId);

        Map<String, String> headers = Map.of(
                "sagaId", sagaId,
                "correlationId", correlationId,
                "status", "COMPENSATED"
        );

        outboxService.saveWithRouting(
                reply,
                AGGREGATE_TYPE,
                sagaId,
                replyTopic,
                sagaId,
                MessagingUtils.toJson(headers, objectMapper)
        );

        log.info("Published COMPENSATED saga reply for saga {} step {}", sagaId, sagaStep);
    }

}

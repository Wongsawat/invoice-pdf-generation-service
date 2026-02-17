package com.wpanther.invoice.pdf.domain.event;

import com.wpanther.saga.domain.enums.ReplyStatus;
import com.wpanther.saga.domain.model.SagaReply;

/**
 * Saga reply event for invoice PDF generation service.
 * Published to Kafka topic: saga.reply.invoice-pdf
 */
public class InvoicePdfReplyEvent extends SagaReply {

    private static final long serialVersionUID = 1L;

    public static InvoicePdfReplyEvent success(String sagaId, String sagaStep, String correlationId) {
        return new InvoicePdfReplyEvent(sagaId, sagaStep, correlationId, ReplyStatus.SUCCESS);
    }

    public static InvoicePdfReplyEvent failure(String sagaId, String sagaStep, String correlationId,
                                                String errorMessage) {
        return new InvoicePdfReplyEvent(sagaId, sagaStep, correlationId, errorMessage);
    }

    public static InvoicePdfReplyEvent compensated(String sagaId, String sagaStep, String correlationId) {
        return new InvoicePdfReplyEvent(sagaId, sagaStep, correlationId, ReplyStatus.COMPENSATED);
    }

    private InvoicePdfReplyEvent(String sagaId, String sagaStep, String correlationId, ReplyStatus status) {
        super(sagaId, sagaStep, correlationId, status);
    }

    private InvoicePdfReplyEvent(String sagaId, String sagaStep, String correlationId, String errorMessage) {
        super(sagaId, sagaStep, correlationId, errorMessage);
    }
}

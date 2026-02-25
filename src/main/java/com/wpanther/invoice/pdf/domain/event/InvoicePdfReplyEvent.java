package com.wpanther.invoice.pdf.domain.event;

import com.wpanther.saga.domain.enums.ReplyStatus;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.saga.domain.model.SagaReply;

/**
 * Saga reply event for invoice PDF generation service.
 * Published to Kafka topic: saga.reply.invoice-pdf
 *
 * SUCCESS replies include pdfUrl and pdfSize so that orchestrator
 * can forward MinIO URL to PDF_STORAGE step.
 */
public class InvoicePdfReplyEvent extends SagaReply {

    private static final long serialVersionUID = 1L;

    private String pdfUrl;
    private Long pdfSize;

    public static InvoicePdfReplyEvent success(String sagaId, SagaStep sagaStep, String correlationId,
                                               String pdfUrl, Long pdfSize) {
        InvoicePdfReplyEvent reply = new InvoicePdfReplyEvent(sagaId, sagaStep, correlationId, ReplyStatus.SUCCESS);
        reply.pdfUrl = pdfUrl;
        reply.pdfSize = pdfSize;
        return reply;
    }

    public static InvoicePdfReplyEvent failure(String sagaId, SagaStep sagaStep, String correlationId,
                                               String errorMessage) {
        return new InvoicePdfReplyEvent(sagaId, sagaStep, correlationId, errorMessage);
    }

    public static InvoicePdfReplyEvent compensated(String sagaId, SagaStep sagaStep, String correlationId) {
        return new InvoicePdfReplyEvent(sagaId, sagaStep, correlationId, ReplyStatus.COMPENSATED);
    }

    private InvoicePdfReplyEvent(String sagaId, SagaStep sagaStep, String correlationId, ReplyStatus status) {
        super(sagaId, sagaStep, correlationId, status);
    }

    private InvoicePdfReplyEvent(String sagaId, SagaStep sagaStep, String correlationId, String errorMessage) {
        super(sagaId, sagaStep, correlationId, errorMessage);
    }

    public String getPdfUrl() {
        return pdfUrl;
    }

    public Long getPdfSize() {
        return pdfSize;
    }
}

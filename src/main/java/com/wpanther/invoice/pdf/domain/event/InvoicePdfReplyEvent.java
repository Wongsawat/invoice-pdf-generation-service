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

    private final String pdfUrl;
    /** 0L for FAILURE / COMPENSATED replies — only meaningful when status == SUCCESS. */
    private final long pdfSize;

    public static InvoicePdfReplyEvent success(String sagaId, SagaStep sagaStep, String correlationId,
                                               String pdfUrl, long pdfSize) {
        return new InvoicePdfReplyEvent(sagaId, sagaStep, correlationId, ReplyStatus.SUCCESS, pdfUrl, pdfSize);
    }

    public static InvoicePdfReplyEvent failure(String sagaId, SagaStep sagaStep, String correlationId,
                                               String errorMessage) {
        return new InvoicePdfReplyEvent(sagaId, sagaStep, correlationId, errorMessage);
    }

    public static InvoicePdfReplyEvent compensated(String sagaId, SagaStep sagaStep, String correlationId) {
        return new InvoicePdfReplyEvent(sagaId, sagaStep, correlationId, ReplyStatus.COMPENSATED);
    }

    private InvoicePdfReplyEvent(String sagaId, SagaStep sagaStep, String correlationId, ReplyStatus status,
                                 String pdfUrl, long pdfSize) {
        super(sagaId, sagaStep, correlationId, status);
        this.pdfUrl = pdfUrl;
        this.pdfSize = pdfSize;
    }

    private InvoicePdfReplyEvent(String sagaId, SagaStep sagaStep, String correlationId, ReplyStatus status) {
        super(sagaId, sagaStep, correlationId, status);
        this.pdfUrl = null;
        this.pdfSize = 0L;
    }

    private InvoicePdfReplyEvent(String sagaId, SagaStep sagaStep, String correlationId, String errorMessage) {
        super(sagaId, sagaStep, correlationId, errorMessage);
        this.pdfUrl = null;
        this.pdfSize = 0L;
    }

    public String getPdfUrl() {
        return pdfUrl;
    }

    public long getPdfSize() {
        return pdfSize;
    }
}

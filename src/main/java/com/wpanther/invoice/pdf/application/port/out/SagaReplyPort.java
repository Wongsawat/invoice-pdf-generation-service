package com.wpanther.invoice.pdf.application.port.out;

import com.wpanther.saga.domain.enums.SagaStep;

/**
 * Output port for publishing saga reply events to the orchestrator.
 * Implementations write to the transactional outbox — callers must run within an active transaction.
 */
public interface SagaReplyPort {

    void publishSuccess(String sagaId, SagaStep sagaStep, String correlationId,
                        String pdfUrl, long pdfSize);

    void publishFailure(String sagaId, SagaStep sagaStep, String correlationId,
                        String errorMessage);

    void publishCompensated(String sagaId, SagaStep sagaStep, String correlationId);
}

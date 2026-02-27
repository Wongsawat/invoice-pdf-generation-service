package com.wpanther.invoice.pdf.domain.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.saga.domain.model.SagaCommand;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Compensation command from Saga Orchestrator to undo PDF generation.
 * Consumed from Kafka topic: saga.compensation.invoice-pdf
 */
@Getter
public class CompensateInvoicePdfCommand extends SagaCommand {

    private static final long serialVersionUID = 1L;

    @JsonProperty("documentId")
    private final String documentId;

    @JsonProperty("invoiceId")
    private final String invoiceId;

    @JsonCreator
    public CompensateInvoicePdfCommand(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("version") int version,
            @JsonProperty("sagaId") String sagaId,
            @JsonProperty("sagaStep") SagaStep sagaStep,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("documentId") String documentId,
            @JsonProperty("invoiceId") String invoiceId) {
        super(eventId, occurredAt, eventType, version, sagaId, sagaStep, correlationId);
        this.documentId = documentId;
        this.invoiceId = invoiceId;
    }

    /**
     * Convenience constructor for testing.
     */
    public CompensateInvoicePdfCommand(String sagaId, SagaStep sagaStep, String correlationId,
                                        String documentId, String invoiceId) {
        super(sagaId, sagaStep, correlationId);
        this.documentId = documentId;
        this.invoiceId = Objects.requireNonNull(invoiceId, "invoiceId is required");
    }
}

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
 * Command received from Saga Orchestrator to generate invoice PDF.
 * Consumed from Kafka topic: saga.command.invoice-pdf
 */
@Getter
public class ProcessInvoicePdfCommand extends SagaCommand {

    private static final long serialVersionUID = 1L;

    @JsonProperty("documentId")
    private final String documentId;

    @JsonProperty("invoiceId")
    private final String invoiceId;

    @JsonProperty("invoiceNumber")
    private final String invoiceNumber;

    @JsonProperty("signedXmlUrl")
    private final String signedXmlUrl;

    @JsonProperty("invoiceDataJson")
    private final String invoiceDataJson;

    @JsonCreator
    public ProcessInvoicePdfCommand(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("version") int version,
            @JsonProperty("sagaId") String sagaId,
            @JsonProperty("sagaStep") SagaStep sagaStep,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("documentId") String documentId,
            @JsonProperty("invoiceId") String invoiceId,
            @JsonProperty("invoiceNumber") String invoiceNumber,
            @JsonProperty("signedXmlUrl") String signedXmlUrl,
            @JsonProperty("invoiceDataJson") String invoiceDataJson) {
        super(eventId, occurredAt, eventType, version, sagaId, sagaStep, correlationId);
        this.documentId = documentId;
        this.invoiceId = invoiceId;
        this.invoiceNumber = invoiceNumber;
        this.signedXmlUrl = signedXmlUrl;
        this.invoiceDataJson = invoiceDataJson;
    }

    /**
     * Convenience constructor for testing.
     */
    public ProcessInvoicePdfCommand(String sagaId, SagaStep sagaStep, String correlationId,
                                     String documentId, String invoiceId, String invoiceNumber,
                                     String signedXmlUrl, String invoiceDataJson) {
        super(sagaId, sagaStep, correlationId);
        this.documentId = Objects.requireNonNull(documentId, "documentId is required");
        this.invoiceId = Objects.requireNonNull(invoiceId, "invoiceId is required");
        this.invoiceNumber = Objects.requireNonNull(invoiceNumber, "invoiceNumber is required");
        this.signedXmlUrl = Objects.requireNonNull(signedXmlUrl, "signedXmlUrl is required");
        this.invoiceDataJson = invoiceDataJson;
    }
}

package com.wpanther.invoice.pdf.domain.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wpanther.saga.domain.model.IntegrationEvent;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Command received from Saga Orchestrator to generate invoice PDF.
 * Consumed from Kafka topic: saga.command.invoice-pdf
 */
@Getter
public class ProcessInvoicePdfCommand extends IntegrationEvent {

    private static final long serialVersionUID = 1L;

    @JsonProperty("sagaId")
    private final String sagaId;

    @JsonProperty("sagaStep")
    private final String sagaStep;

    @JsonProperty("correlationId")
    private final String correlationId;

    @JsonProperty("documentId")
    private final String documentId;

    @JsonProperty("invoiceId")
    private final String invoiceId;

    @JsonProperty("invoiceNumber")
    private final String invoiceNumber;

    @JsonProperty("signedXmlContent")
    private final String signedXmlContent;

    @JsonProperty("invoiceDataJson")
    private final String invoiceDataJson;

    @JsonCreator
    public ProcessInvoicePdfCommand(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("version") int version,
            @JsonProperty("sagaId") String sagaId,
            @JsonProperty("sagaStep") String sagaStep,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("documentId") String documentId,
            @JsonProperty("invoiceId") String invoiceId,
            @JsonProperty("invoiceNumber") String invoiceNumber,
            @JsonProperty("signedXmlContent") String signedXmlContent,
            @JsonProperty("invoiceDataJson") String invoiceDataJson) {
        super(eventId, occurredAt, eventType, version);
        this.sagaId = sagaId;
        this.sagaStep = sagaStep;
        this.correlationId = correlationId;
        this.documentId = documentId;
        this.invoiceId = invoiceId;
        this.invoiceNumber = invoiceNumber;
        this.signedXmlContent = signedXmlContent;
        this.invoiceDataJson = invoiceDataJson;
    }

    /**
     * Convenience constructor for testing.
     */
    public ProcessInvoicePdfCommand(String sagaId, String sagaStep, String correlationId,
                                     String documentId, String invoiceId, String invoiceNumber,
                                     String signedXmlContent, String invoiceDataJson) {
        super();
        this.sagaId = sagaId;
        this.sagaStep = sagaStep;
        this.correlationId = correlationId;
        this.documentId = documentId;
        this.invoiceId = invoiceId;
        this.invoiceNumber = invoiceNumber;
        this.signedXmlContent = signedXmlContent;
        this.invoiceDataJson = invoiceDataJson;
    }
}

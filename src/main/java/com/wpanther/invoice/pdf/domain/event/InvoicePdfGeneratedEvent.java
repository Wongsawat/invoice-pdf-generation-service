package com.wpanther.invoice.pdf.domain.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wpanther.saga.domain.model.TraceEvent;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Event published when an invoice PDF has been generated.
 * Consumed by notification-service.
 */
@Getter
public class InvoicePdfGeneratedEvent extends TraceEvent {

    private static final String EVENT_TYPE = "pdf.generated.invoice";
    private static final String SOURCE = "invoice-pdf-generation-service";
    private static final String TRACE_TYPE = "PDF_GENERATED";

    @JsonProperty("documentId")
    private final String documentId;

    @JsonProperty("invoiceId")
    private final String invoiceId;

    @JsonProperty("invoiceNumber")
    private final String invoiceNumber;

    @JsonProperty("documentUrl")
    private final String documentUrl;

    @JsonProperty("fileSize")
    private final long fileSize;

    @JsonProperty("xmlEmbedded")
    private final boolean xmlEmbedded;

    /**
     * Convenience constructor. correlationId is stored as sagaId in TraceEvent.
     */
    public InvoicePdfGeneratedEvent(
            String documentId,
            String invoiceId,
            String invoiceNumber,
            String documentUrl,
            long fileSize,
            boolean xmlEmbedded,
            String correlationId
    ) {
        super(correlationId, SOURCE, TRACE_TYPE, null);
        this.documentId = documentId;
        this.invoiceId = invoiceId;
        this.invoiceNumber = invoiceNumber;
        this.documentUrl = documentUrl;
        this.fileSize = fileSize;
        this.xmlEmbedded = xmlEmbedded;
    }

    /**
     * Returns the correlation ID (stored as sagaId in TraceEvent).
     */
    @JsonIgnore
    public String getCorrelationId() {
        return getSagaId();
    }

    @Override
    public String getEventType() {
        return EVENT_TYPE;
    }

    @JsonCreator
    public InvoicePdfGeneratedEvent(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("version") int version,
            @JsonProperty("sagaId") String sagaId,
            @JsonProperty("source") String source,
            @JsonProperty("traceType") String traceType,
            @JsonProperty("context") String context,
            @JsonProperty("documentId") String documentId,
            @JsonProperty("invoiceId") String invoiceId,
            @JsonProperty("invoiceNumber") String invoiceNumber,
            @JsonProperty("documentUrl") String documentUrl,
            @JsonProperty("fileSize") long fileSize,
            @JsonProperty("xmlEmbedded") boolean xmlEmbedded
    ) {
        super(eventId, occurredAt, eventType, version, sagaId, source, traceType, context);
        this.documentId = documentId;
        this.invoiceId = invoiceId;
        this.invoiceNumber = invoiceNumber;
        this.documentUrl = documentUrl;
        this.fileSize = fileSize;
        this.xmlEmbedded = xmlEmbedded;
    }
}

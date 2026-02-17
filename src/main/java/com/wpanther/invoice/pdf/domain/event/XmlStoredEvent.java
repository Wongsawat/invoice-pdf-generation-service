package com.wpanther.invoice.pdf.domain.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wpanther.saga.domain.model.IntegrationEvent;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Event published when a signed XML document is stored in MinIO/S3.
 * Consumed by invoice-pdf-generation-service to trigger PDF generation.
 * Produced by document-storage-service.
 */
@Getter
public class XmlStoredEvent extends IntegrationEvent {

    private static final String EVENT_TYPE = "XmlStoredEvent";

    @JsonProperty("invoiceId")
    private final String invoiceId;

    @JsonProperty("storageUrl")
    private final String storageUrl;

    @JsonProperty("objectName")
    private final String objectName;

    @JsonProperty("documentType")
    private final String documentType;

    @JsonProperty("invoiceNumber")
    private final String invoiceNumber;

    @JsonProperty("correlationId")
    private final String correlationId;

    /**
     * JsonCreator for deserialization from Kafka/Debezium.
     */
    @JsonCreator
    public XmlStoredEvent(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("version") int version,
            @JsonProperty("invoiceId") String invoiceId,
            @JsonProperty("storageUrl") String storageUrl,
            @JsonProperty("objectName") String objectName,
            @JsonProperty("documentType") String documentType,
            @JsonProperty("invoiceNumber") String invoiceNumber,
            @JsonProperty("correlationId") String correlationId) {
        super(eventId, occurredAt, eventType, version);
        this.invoiceId = invoiceId;
        this.storageUrl = storageUrl;
        this.objectName = objectName;
        this.documentType = documentType;
        this.invoiceNumber = invoiceNumber;
        this.correlationId = correlationId;
    }
}

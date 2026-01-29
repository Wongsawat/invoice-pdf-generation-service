package com.wpanther.invoice.pdf.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Event published when an invoice PDF has been generated
 */
public class InvoicePdfGeneratedEvent {

    private final String eventId;
    private final String eventType = "pdf.generated.invoice";
    private final Instant occurredAt;
    private final int version = 1;
    private final String documentId;
    private final String invoiceId;
    private final String invoiceNumber;
    private final String documentUrl;
    private final long fileSize;
    private final boolean xmlEmbedded;
    private final String correlationId;

    public InvoicePdfGeneratedEvent(
        String documentId,
        String invoiceId,
        String invoiceNumber,
        String documentUrl,
        long fileSize,
        boolean xmlEmbedded,
        String correlationId
    ) {
        this.eventId = UUID.randomUUID().toString();
        this.occurredAt = Instant.now();
        this.documentId = documentId;
        this.invoiceId = invoiceId;
        this.invoiceNumber = invoiceNumber;
        this.documentUrl = documentUrl;
        this.fileSize = fileSize;
        this.xmlEmbedded = xmlEmbedded;
        this.correlationId = correlationId;
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public int getVersion() {
        return version;
    }

    public String getDocumentId() {
        return documentId;
    }

    public String getInvoiceId() {
        return invoiceId;
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public String getDocumentUrl() {
        return documentUrl;
    }

    public long getFileSize() {
        return fileSize;
    }

    public boolean isXmlEmbedded() {
        return xmlEmbedded;
    }

    public String getCorrelationId() {
        return correlationId;
    }
}

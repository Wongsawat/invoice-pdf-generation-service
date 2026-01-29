package com.wpanther.invoice.pdf.domain.event;

/**
 * External event consumed when an invoice XML has been signed
 * This event is published by the XML Signing Service
 */
public class XmlSignedInvoiceEvent {

    private final String documentId;
    private final String invoiceId;
    private final String invoiceNumber;
    private final String signedXmlContent;
    private final String invoiceDataJson;
    private final String correlationId;

    public XmlSignedInvoiceEvent(
        String documentId,
        String invoiceId,
        String invoiceNumber,
        String signedXmlContent,
        String invoiceDataJson,
        String correlationId
    ) {
        this.documentId = documentId;
        this.invoiceId = invoiceId;
        this.invoiceNumber = invoiceNumber;
        this.signedXmlContent = signedXmlContent;
        this.invoiceDataJson = invoiceDataJson;
        this.correlationId = correlationId;
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

    public String getSignedXmlContent() {
        return signedXmlContent;
    }

    public String getInvoiceDataJson() {
        return invoiceDataJson;
    }

    public String getCorrelationId() {
        return correlationId;
    }
}

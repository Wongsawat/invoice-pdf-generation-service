package com.wpanther.invoice.pdf.domain.service;

import com.wpanther.invoice.pdf.domain.exception.InvoicePdfGenerationException;

/**
 * Domain service for Invoice PDF generation
 */
public interface InvoicePdfGenerationService {

    /**
     * Generate PDF from invoice data
     *
     * @param invoiceNumber Invoice number
     * @param xmlContent XML content to embed
     * @param invoiceDataJson JSON data for template
     * @return PDF bytes
     * @throws InvoicePdfGenerationException if generation fails
     */
    byte[] generatePdf(String invoiceNumber, String xmlContent, String invoiceDataJson)
        throws InvoicePdfGenerationException;
}

package com.wpanther.invoice.pdf.domain.service;

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

    /**
     * Exception thrown when PDF generation fails
     */
    class InvoicePdfGenerationException extends Exception {
        public InvoicePdfGenerationException(String message) {
            super(message);
        }

        public InvoicePdfGenerationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

package com.wpanther.invoice.pdf.application.port.out;

/**
 * Output port for PDF document storage.
 * The application layer depends on this interface; the MinIO/S3 adapter lives in infrastructure.
 */
public interface PdfStoragePort {

    /**
     * Store the given PDF bytes and return the storage key (path) under which
     * the file was saved.  Callers combine this key with {@link #resolveUrl(String)}
     * to build the public URL.
     *
     * @param invoiceNumber invoice number used to derive the file name
     * @param pdfBytes      raw PDF content
     * @return the storage key, e.g. {@code 2024/01/15/invoice-INV-001-uuid.pdf}
     */
    String store(String invoiceNumber, byte[] pdfBytes);

    /**
     * Delete the PDF identified by the given storage key.
     *
     * @param key storage key returned by {@link #store}
     */
    void delete(String key);

    /**
     * Resolve a storage key to its public access URL.
     *
     * @param key storage key
     * @return full URL, e.g. {@code http://localhost:9001/invoices/2024/01/15/invoice-...pdf}
     */
    String resolveUrl(String key);
}

package com.wpanther.invoice.pdf.domain.repository;

import com.wpanther.invoice.pdf.domain.model.InvoicePdfDocument;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for InvoicePdfDocument aggregate
 */
public interface InvoicePdfDocumentRepository {

    /**
     * Save invoice PDF document
     */
    InvoicePdfDocument save(InvoicePdfDocument document);

    /**
     * Find by ID
     */
    Optional<InvoicePdfDocument> findById(UUID id);

    /**
     * Find by invoice ID
     */
    Optional<InvoicePdfDocument> findByInvoiceId(String invoiceId);

    /**
     * Delete by ID
     */
    void deleteById(UUID id);

    /**
     * Flush pending changes to the database.
     * Required to order a DELETE before a subsequent INSERT on the same unique key
     * within the same transaction (e.g., delete-then-recreate on saga retry).
     */
    void flush();
}

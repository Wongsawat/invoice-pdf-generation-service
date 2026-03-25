package com.wpanther.invoice.pdf.infrastructure.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface JpaInvoicePdfDocumentRepository extends JpaRepository<InvoicePdfDocumentEntity, UUID> {

    Optional<InvoicePdfDocumentEntity> findByInvoiceId(String invoiceId);

    /**
     * Find all document paths for PDFs that have been successfully generated and committed.
     * <p>
     * Used by orphaned PDF cleanup job to reconcile against MinIO objects.
     * Only returns paths where status is COMPLETED (orphaned objects from failed
     * generations are intentionally excluded to avoid false positives).
     *
     * @return set of all S3 object keys for successfully generated PDFs
     */
    @Query("SELECT d.documentPath FROM InvoicePdfDocumentEntity d WHERE d.documentPath IS NOT NULL AND d.status = 'COMPLETED'")
    Set<String> findAllDocumentPaths();
}

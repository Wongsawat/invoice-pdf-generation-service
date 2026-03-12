package com.wpanther.invoice.pdf.infrastructure.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface JpaInvoicePdfDocumentRepository extends JpaRepository<InvoicePdfDocumentEntity, UUID> {

    Optional<InvoicePdfDocumentEntity> findByInvoiceId(String invoiceId);
}

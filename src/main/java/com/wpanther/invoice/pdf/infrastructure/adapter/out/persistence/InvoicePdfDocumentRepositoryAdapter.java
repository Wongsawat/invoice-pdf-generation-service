package com.wpanther.invoice.pdf.infrastructure.adapter.out.persistence;

import com.wpanther.invoice.pdf.domain.model.InvoicePdfDocument;
import com.wpanther.invoice.pdf.domain.repository.InvoicePdfDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Ports-and-adapters JPA adapter for the domain {@link InvoicePdfDocumentRepository} port.
 * Owns all entity↔domain mapping, keeping infrastructure details out of
 * the application and domain layers.
 *
 * <p>Named {@code InvoicePdfDocumentRepositoryAdapter} (not {@code ...Impl}) to avoid
 * Spring Data JPA's {@code {RepositoryName}Impl} naming convention, which would
 * incorrectly treat this class as a custom-implementation fragment of
 * {@link JpaInvoicePdfDocumentRepository} and create a circular dependency.
 */
@Repository
@RequiredArgsConstructor
public class InvoicePdfDocumentRepositoryAdapter implements InvoicePdfDocumentRepository {

    private final JpaInvoicePdfDocumentRepository jpaRepository;

    @Override
    public InvoicePdfDocument save(InvoicePdfDocument document) {
        InvoicePdfDocumentEntity entity = toEntity(document);
        entity = jpaRepository.save(entity);
        return toDomain(entity);
    }

    @Override
    public Optional<InvoicePdfDocument> findById(UUID id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<InvoicePdfDocument> findByInvoiceId(String invoiceId) {
        return jpaRepository.findByInvoiceId(invoiceId).map(this::toDomain);
    }

    @Override
    public void deleteById(UUID id) {
        jpaRepository.deleteById(id);
    }

    @Override
    public void flush() {
        jpaRepository.flush();
    }

    // -------------------------------------------------------------------------
    // Mapping
    // -------------------------------------------------------------------------

    private InvoicePdfDocumentEntity toEntity(InvoicePdfDocument document) {
        return InvoicePdfDocumentEntity.builder()
            .id(document.getId())
            .invoiceId(document.getInvoiceId())
            .invoiceNumber(document.getInvoiceNumber())
            .documentPath(document.getDocumentPath())
            .documentUrl(document.getDocumentUrl())
            .fileSize(document.getFileSize())
            .mimeType(document.getMimeType())
            .xmlEmbedded(document.isXmlEmbedded())
            .status(document.getStatus())
            .errorMessage(document.getErrorMessage())
            .retryCount(document.getRetryCount())
            .createdAt(document.getCreatedAt())
            .completedAt(document.getCompletedAt())
            .version(document.getVersion())
            .build();
    }

    private InvoicePdfDocument toDomain(InvoicePdfDocumentEntity entity) {
        return InvoicePdfDocument.builder()
            .id(entity.getId())
            .invoiceId(entity.getInvoiceId())
            .invoiceNumber(entity.getInvoiceNumber())
            .documentPath(entity.getDocumentPath())
            .documentUrl(entity.getDocumentUrl())
            .fileSize(entity.getFileSize())
            .mimeType(entity.getMimeType())
            .xmlEmbedded(Boolean.TRUE.equals(entity.getXmlEmbedded()))
            .status(entity.getStatus())
            .errorMessage(entity.getErrorMessage())
            .retryCount(entity.getRetryCount())
            .createdAt(entity.getCreatedAt())
            .completedAt(entity.getCompletedAt())
            .version(entity.getVersion())
            .build();
    }
}

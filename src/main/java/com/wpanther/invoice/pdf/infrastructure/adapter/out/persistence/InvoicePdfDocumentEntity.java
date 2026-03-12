package com.wpanther.invoice.pdf.infrastructure.adapter.out.persistence;

import com.wpanther.invoice.pdf.domain.model.GenerationStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "invoice_pdf_documents",
        uniqueConstraints = @UniqueConstraint(name = "uq_invoice_pdf_invoice_id", columnNames = "invoice_id"),
        indexes = {
    @Index(name = "idx_invoice_pdf_invoice_id", columnList = "invoice_id"),
    @Index(name = "idx_invoice_pdf_invoice_number", columnList = "invoice_number"),
    @Index(name = "idx_invoice_pdf_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PACKAGE)   // package-private: used by @Builder only
@Builder
public class InvoicePdfDocumentEntity {

    static final int COL_LEN_ID           = 100;
    static final int COL_LEN_INVOICE_NUM  = 50;
    static final int COL_LEN_DOC_PATH     = 500;
    static final int COL_LEN_DOC_URL      = 1000;
    static final int COL_LEN_MIME_TYPE    = 100;
    static final int COL_LEN_STATUS       = 20;

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "invoice_id", nullable = false, length = COL_LEN_ID)
    private String invoiceId;

    @Column(name = "invoice_number", nullable = false, length = COL_LEN_INVOICE_NUM)
    private String invoiceNumber;

    @Column(name = "document_path", length = COL_LEN_DOC_PATH)
    private String documentPath;

    @Column(name = "document_url", length = COL_LEN_DOC_URL)
    private String documentUrl;

    @Column(name = "file_size", nullable = false, columnDefinition = "BIGINT DEFAULT 0")
    private Long fileSize;

    @Column(name = "mime_type", nullable = false, length = COL_LEN_MIME_TYPE)
    private String mimeType;

    @Column(name = "xml_embedded", nullable = false)
    private Boolean xmlEmbedded;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = COL_LEN_STATUS)
    private GenerationStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "retry_count", nullable = false, columnDefinition = "INTEGER DEFAULT 0")
    private Integer retryCount;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    // createdAt is mapped explicitly from the domain model via toEntity().
    // InvoicePdfDocument.Builder defaults to LocalDateTime.now() when not set,
    // so this field is always non-null on first persist.
    // @CreationTimestamp was removed: it would silently overwrite the domain-supplied
    // value on every INSERT, making round-trip timestamps unreliable.
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        // Defence-in-depth: the normal path always supplies createdAt via toEntity()
        // (the domain model Builder defaults to LocalDateTime.now()). This guard
        // prevents a NOT NULL constraint failure if the entity is ever constructed
        // directly via its own Lombok @Builder without going through the domain model.
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = GenerationStatus.PENDING;
        }
        if (mimeType == null) {
            mimeType = "application/pdf";
        }
        if (xmlEmbedded == null) {
            xmlEmbedded = false;
        }
        if (retryCount == null) {
            retryCount = 0;
        }
    }
}

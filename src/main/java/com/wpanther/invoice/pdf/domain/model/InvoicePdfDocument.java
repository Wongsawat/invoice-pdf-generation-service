package com.wpanther.invoice.pdf.domain.model;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate Root representing an Invoice PDF document
 *
 * This aggregate encapsulates the PDF generation lifecycle including
 * generation and XML embedding for invoices.
 */
public class InvoicePdfDocument {

    private static final String DEFAULT_MIME_TYPE = "application/pdf";

    // Identity
    private final UUID id;

    // Invoice Reference
    private final String invoiceId;
    private final String invoiceNumber;

    // Document Location
    private String documentPath;
    private String documentUrl;

    // Document Metadata
    private long fileSize;
    private final String mimeType;
    private boolean xmlEmbedded;

    // Status
    private GenerationStatus status;
    private String errorMessage;

    // Retry tracking
    private int retryCount;

    // Timestamps
    private final LocalDateTime createdAt;
    private LocalDateTime completedAt;

    // Optimistic locking (persistence concern — round-trips through the repository adapter)
    private Long version;

    private InvoicePdfDocument(Builder builder) {
        this.id = builder.id != null ? builder.id : UUID.randomUUID();
        this.invoiceId = Objects.requireNonNull(builder.invoiceId, "Invoice ID is required");
        this.invoiceNumber = Objects.requireNonNull(builder.invoiceNumber, "Invoice number is required");
        this.documentPath = builder.documentPath;
        this.documentUrl = builder.documentUrl;
        this.fileSize = builder.fileSize;
        this.mimeType = builder.mimeType != null ? builder.mimeType : DEFAULT_MIME_TYPE;
        this.xmlEmbedded = builder.xmlEmbedded;
        this.status = builder.status != null ? builder.status : GenerationStatus.PENDING;
        this.errorMessage = builder.errorMessage;
        this.retryCount = builder.retryCount;
        this.createdAt = builder.createdAt != null ? builder.createdAt : LocalDateTime.now();
        this.completedAt = builder.completedAt;
        this.version = builder.version;

        validateInvariant();
    }

    /**
     * Validate business invariants
     */
    private void validateInvariant() {
        if (invoiceId.isBlank()) {
            throw new IllegalStateException("Invoice ID cannot be blank");
        }

        if (invoiceNumber.isBlank()) {
            throw new IllegalStateException("Invoice number cannot be blank");
        }
    }

    /**
     * Start PDF generation
     */
    public void startGeneration() {
        if (this.status != GenerationStatus.PENDING) {
            throw new IllegalStateException("Can only start generation from PENDING status");
        }
        this.status = GenerationStatus.GENERATING;
    }

    /**
     * Mark generation as completed
     */
    public void markCompleted(String documentPath, String documentUrl, long fileSize, LocalDateTime completedAt) {
        if (this.status != GenerationStatus.GENERATING) {
            throw new IllegalStateException("Can only complete from GENERATING status");
        }

        Objects.requireNonNull(documentPath, "Document path is required");
        Objects.requireNonNull(documentUrl, "Document URL is required");

        if (fileSize <= 0) {
            throw new IllegalArgumentException("File size must be positive");
        }

        this.documentPath = documentPath;
        this.documentUrl = documentUrl;
        this.fileSize = fileSize;
        this.status = GenerationStatus.COMPLETED;
        this.completedAt = completedAt;
    }

    /**
     * Mark generation as failed
     */
    public void markFailed(String errorMessage, LocalDateTime failedAt) {
        if (this.status == GenerationStatus.COMPLETED) {
            throw new IllegalStateException("Cannot mark a COMPLETED document as failed");
        }
        this.status = GenerationStatus.FAILED;
        this.errorMessage = errorMessage;
        this.completedAt = failedAt;
    }

    /**
     * Mark XML as embedded
     */
    public void markXmlEmbedded() {
        this.xmlEmbedded = true;
    }

    public boolean isCompleted() {
        return status == GenerationStatus.COMPLETED;
    }

    public boolean isFailed() {
        return status == GenerationStatus.FAILED;
    }

    // Getters
    public UUID getId() {
        return id;
    }

    public String getInvoiceId() {
        return invoiceId;
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public String getDocumentPath() {
        return documentPath;
    }

    public String getDocumentUrl() {
        return documentUrl;
    }

    public long getFileSize() {
        return fileSize;
    }

    public String getMimeType() {
        return mimeType;
    }

    public boolean isXmlEmbedded() {
        return xmlEmbedded;
    }

    public GenerationStatus getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public Long getVersion() {
        return version;
    }

    public boolean isMaxRetriesExceeded(int maxRetries) {
        return this.retryCount >= maxRetries;
    }

    /**
     * Advance the retry count to {@code target} if it is not already there.
     * Used by the application service when carrying forward the count from a
     * previous failed attempt.
     */
    public void incrementRetryCountTo(int target) {
        if (this.retryCount < target) {
            this.retryCount = target;
        }
    }

    /**
     * Builder for InvoicePdfDocument
     */
    public static class Builder {
        private UUID id;
        private String invoiceId;
        private String invoiceNumber;
        private String documentPath;
        private String documentUrl;
        private long fileSize;
        private String mimeType;
        private boolean xmlEmbedded;
        private GenerationStatus status;
        private String errorMessage;
        private int retryCount;
        private LocalDateTime createdAt;
        private LocalDateTime completedAt;
        private Long version;

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder invoiceId(String invoiceId) {
            this.invoiceId = invoiceId;
            return this;
        }

        public Builder invoiceNumber(String invoiceNumber) {
            this.invoiceNumber = invoiceNumber;
            return this;
        }

        public Builder documentPath(String documentPath) {
            this.documentPath = documentPath;
            return this;
        }

        public Builder documentUrl(String documentUrl) {
            this.documentUrl = documentUrl;
            return this;
        }

        public Builder fileSize(long fileSize) {
            if (fileSize < 0) {
                throw new IllegalArgumentException("fileSize must be non-negative, got: " + fileSize);
            }
            this.fileSize = fileSize;
            return this;
        }

        public Builder mimeType(String mimeType) {
            this.mimeType = mimeType;
            return this;
        }

        public Builder xmlEmbedded(boolean xmlEmbedded) {
            this.xmlEmbedded = xmlEmbedded;
            return this;
        }

        public Builder status(GenerationStatus status) {
            this.status = status;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public Builder retryCount(int retryCount) {
            this.retryCount = retryCount;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder completedAt(LocalDateTime completedAt) {
            this.completedAt = completedAt;
            return this;
        }

        public Builder version(Long version) {
            this.version = version;
            return this;
        }

        public InvoicePdfDocument build() {
            return new InvoicePdfDocument(this);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}

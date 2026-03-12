package com.wpanther.invoice.pdf.domain.exception;

/**
 * Domain exception for invoice PDF generation failures.
 * <p>Use specific error codes for better observability and error handling.</p>
 */
public class InvoicePdfGenerationException extends RuntimeException {

    private final ErrorCode code;

    public InvoicePdfGenerationException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public InvoicePdfGenerationException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /**
     * Convenience constructor for backwards compatibility.
     * Uses {@link ErrorCode#GENERATION_FAILED} as default.
     */
    public InvoicePdfGenerationException(String message) {
        this(ErrorCode.GENERATION_FAILED, message);
    }

    /**
     * Convenience constructor for backwards compatibility.
     * Uses {@link ErrorCode#GENERATION_FAILED} as default.
     */
    public InvoicePdfGenerationException(String message, Throwable cause) {
        this(ErrorCode.GENERATION_FAILED, message, cause);
    }

    public ErrorCode getCode() {
        return code;
    }

    /**
     * Error codes for invoice PDF generation failures.
     * Used for categorizing errors and improving observability.
     */
    public enum ErrorCode {
        /** PDF generation failed (FOP transformation or PDF/A conversion) */
        GENERATION_FAILED,

        /** Storage operation failed (MinIO upload/download) */
        STORAGE_FAILED,

        /** Invalid input data (missing required fields, validation errors) */
        INVALID_INPUT,

        /** Signed XML fetch failed (external service unavailable) */
        SIGNED_XML_FETCH_FAILED,

        /** Database operation failed (optimistic locking, constraint violations) */
        DATABASE_ERROR,

        /** State transition violation (invalid state machine transition) */
        INVALID_STATE
    }
}

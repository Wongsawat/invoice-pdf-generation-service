package com.wpanther.invoice.pdf.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("InvoicePdfDocument Aggregate Tests")
class InvoicePdfDocumentTest {

    private InvoicePdfDocument pendingDocument() {
        return InvoicePdfDocument.builder()
                .invoiceId("inv-001")
                .invoiceNumber("INV-2024-001")
                .build();
    }

    // -------------------------------------------------------------------------
    // Builder / invariants
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should create document in PENDING status with defaults")
    void testCreate_Defaults() {
        InvoicePdfDocument doc = pendingDocument();

        assertThat(doc.getId()).isNotNull();
        assertThat(doc.getStatus()).isEqualTo(GenerationStatus.PENDING);
        assertThat(doc.getMimeType()).isEqualTo("application/pdf");
        assertThat(doc.getRetryCount()).isZero();
        assertThat(doc.isXmlEmbedded()).isFalse();
        assertThat(doc.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Should reject blank invoiceId")
    void testCreate_BlankInvoiceId() {
        assertThatThrownBy(() ->
                InvoicePdfDocument.builder()
                        .invoiceId("   ")
                        .invoiceNumber("INV-001")
                        .build()
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("Invoice ID cannot be blank");
    }

    @Test
    @DisplayName("Should reject null invoiceNumber")
    void testCreate_NullInvoiceNumber() {
        assertThatThrownBy(() ->
                InvoicePdfDocument.builder()
                        .invoiceId("inv-001")
                        .invoiceNumber(null)
                        .build()
        ).isInstanceOf(NullPointerException.class);
    }

    // -------------------------------------------------------------------------
    // State machine — happy path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("PENDING → startGeneration() → GENERATING")
    void testStartGeneration() {
        InvoicePdfDocument doc = pendingDocument();
        doc.startGeneration();
        assertThat(doc.getStatus()).isEqualTo(GenerationStatus.GENERATING);
    }

    @Test
    @DisplayName("GENERATING → markCompleted() → COMPLETED")
    void testMarkCompleted() {
        InvoicePdfDocument doc = pendingDocument();
        doc.startGeneration();
        doc.markCompleted("2024/01/15/test.pdf", "http://minio/test.pdf", 12345L);

        assertThat(doc.getStatus()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(doc.getDocumentPath()).isEqualTo("2024/01/15/test.pdf");
        assertThat(doc.getDocumentUrl()).isEqualTo("http://minio/test.pdf");
        assertThat(doc.getFileSize()).isEqualTo(12345L);
        assertThat(doc.getCompletedAt()).isNotNull();
        assertThat(doc.isCompleted()).isTrue();
        assertThat(doc.isFailed()).isFalse();
    }

    @Test
    @DisplayName("Any state → markFailed() → FAILED")
    void testMarkFailed_FromPending() {
        InvoicePdfDocument doc = pendingDocument();
        doc.markFailed("Something went wrong");

        assertThat(doc.getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(doc.getErrorMessage()).isEqualTo("Something went wrong");
        assertThat(doc.isFailed()).isTrue();
        assertThat(doc.isCompleted()).isFalse();
        assertThat(doc.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("GENERATING → markFailed() → FAILED")
    void testMarkFailed_FromGenerating() {
        InvoicePdfDocument doc = pendingDocument();
        doc.startGeneration();
        doc.markFailed("FOP transform failed");

        assertThat(doc.isFailed()).isTrue();
        assertThat(doc.getErrorMessage()).isEqualTo("FOP transform failed");
    }

    @Test
    @DisplayName("markXmlEmbedded() sets flag")
    void testMarkXmlEmbedded() {
        InvoicePdfDocument doc = pendingDocument();
        assertThat(doc.isXmlEmbedded()).isFalse();
        doc.markXmlEmbedded();
        assertThat(doc.isXmlEmbedded()).isTrue();
    }

    // -------------------------------------------------------------------------
    // State machine — invalid transitions
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("startGeneration() from GENERATING throws IllegalStateException")
    void testStartGeneration_AlreadyGenerating() {
        InvoicePdfDocument doc = pendingDocument();
        doc.startGeneration();

        assertThatThrownBy(doc::startGeneration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    @DisplayName("markCompleted() from PENDING throws IllegalStateException")
    void testMarkCompleted_FromPending() {
        InvoicePdfDocument doc = pendingDocument();

        assertThatThrownBy(() -> doc.markCompleted("path", "url", 100L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GENERATING");
    }

    @Test
    @DisplayName("markCompleted() with zero fileSize throws IllegalArgumentException")
    void testMarkCompleted_ZeroFileSize() {
        InvoicePdfDocument doc = pendingDocument();
        doc.startGeneration();

        assertThatThrownBy(() -> doc.markCompleted("path", "url", 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("File size must be positive");
    }

    @Test
    @DisplayName("markCompleted() with null documentPath throws NullPointerException")
    void testMarkCompleted_NullPath() {
        InvoicePdfDocument doc = pendingDocument();
        doc.startGeneration();

        assertThatThrownBy(() -> doc.markCompleted(null, "url", 100L))
                .isInstanceOf(NullPointerException.class);
    }

    // -------------------------------------------------------------------------
    // Retry tracking
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("incrementRetryCount() increases count by one")
    void testIncrementRetryCount() {
        InvoicePdfDocument doc = pendingDocument();
        assertThat(doc.getRetryCount()).isZero();
        doc.incrementRetryCount();
        assertThat(doc.getRetryCount()).isOne();
        doc.incrementRetryCount();
        assertThat(doc.getRetryCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("isMaxRetriesExceeded() returns true when retryCount >= maxRetries")
    void testIsMaxRetriesExceeded_AtLimit() {
        InvoicePdfDocument doc = InvoicePdfDocument.builder()
                .invoiceId("inv-001")
                .invoiceNumber("INV-001")
                .retryCount(3)
                .build();
        assertThat(doc.isMaxRetriesExceeded(3)).isTrue();
    }

    @Test
    @DisplayName("isMaxRetriesExceeded() returns false when retryCount < maxRetries")
    void testIsMaxRetriesExceeded_BelowLimit() {
        InvoicePdfDocument doc = InvoicePdfDocument.builder()
                .invoiceId("inv-001")
                .invoiceNumber("INV-001")
                .retryCount(2)
                .build();
        assertThat(doc.isMaxRetriesExceeded(3)).isFalse();
    }
}

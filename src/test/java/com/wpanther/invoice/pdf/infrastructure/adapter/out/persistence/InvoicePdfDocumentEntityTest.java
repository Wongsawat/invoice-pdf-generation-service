package com.wpanther.invoice.pdf.infrastructure.adapter.out.persistence;

import com.wpanther.invoice.pdf.domain.model.GenerationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InvoicePdfDocumentEntity @PrePersist defaults")
class InvoicePdfDocumentEntityTest {

    @Test
    @DisplayName("onCreate generates a UUID when id is null")
    void onCreate_nullId_generatesUuid() {
        InvoicePdfDocumentEntity entity = new InvoicePdfDocumentEntity();
        entity.onCreate();
        assertThat(entity.getId()).isNotNull();
    }

    @Test
    @DisplayName("onCreate preserves an existing id")
    void onCreate_existingId_preserved() {
        UUID existingId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        InvoicePdfDocumentEntity entity = InvoicePdfDocumentEntity.builder()
                .id(existingId).invoiceId("inv-1").invoiceNumber("INV-001").build();
        entity.onCreate();
        assertThat(entity.getId()).isEqualTo(existingId);
    }

    @Test
    @DisplayName("onCreate sets status to PENDING when null")
    void onCreate_nullStatus_setPending() {
        InvoicePdfDocumentEntity entity = new InvoicePdfDocumentEntity();
        entity.onCreate();
        assertThat(entity.getStatus()).isEqualTo(GenerationStatus.PENDING);
    }

    @Test
    @DisplayName("onCreate preserves an existing status")
    void onCreate_existingStatus_preserved() {
        InvoicePdfDocumentEntity entity = InvoicePdfDocumentEntity.builder()
                .invoiceId("inv-1").invoiceNumber("INV-001")
                .status(GenerationStatus.COMPLETED).build();
        entity.onCreate();
        assertThat(entity.getStatus()).isEqualTo(GenerationStatus.COMPLETED);
    }

    @Test
    @DisplayName("onCreate sets mimeType to application/pdf when null")
    void onCreate_nullMimeType_setApplicationPdf() {
        InvoicePdfDocumentEntity entity = new InvoicePdfDocumentEntity();
        entity.onCreate();
        assertThat(entity.getMimeType()).isEqualTo("application/pdf");
    }

    @Test
    @DisplayName("onCreate preserves an existing mimeType")
    void onCreate_existingMimeType_preserved() {
        InvoicePdfDocumentEntity entity = InvoicePdfDocumentEntity.builder()
                .invoiceId("inv-1").invoiceNumber("INV-001")
                .mimeType("application/octet-stream").build();
        entity.onCreate();
        assertThat(entity.getMimeType()).isEqualTo("application/octet-stream");
    }

    @Test
    @DisplayName("onCreate sets xmlEmbedded to false when null")
    void onCreate_nullXmlEmbedded_setFalse() {
        InvoicePdfDocumentEntity entity = new InvoicePdfDocumentEntity();
        entity.onCreate();
        assertThat(entity.getXmlEmbedded()).isFalse();
    }

    @Test
    @DisplayName("onCreate preserves xmlEmbedded=true when already set")
    void onCreate_existingXmlEmbeddedTrue_preserved() {
        InvoicePdfDocumentEntity entity = InvoicePdfDocumentEntity.builder()
                .invoiceId("inv-1").invoiceNumber("INV-001")
                .xmlEmbedded(true).build();
        entity.onCreate();
        assertThat(entity.getXmlEmbedded()).isTrue();
    }

    @Test
    @DisplayName("onCreate sets retryCount to 0 when null")
    void onCreate_nullRetryCount_setZero() {
        InvoicePdfDocumentEntity entity = new InvoicePdfDocumentEntity();
        entity.onCreate();
        assertThat(entity.getRetryCount()).isZero();
    }

    @Test
    @DisplayName("onCreate preserves an existing retryCount")
    void onCreate_existingRetryCount_preserved() {
        InvoicePdfDocumentEntity entity = InvoicePdfDocumentEntity.builder()
                .invoiceId("inv-1").invoiceNumber("INV-001")
                .retryCount(2).build();
        entity.onCreate();
        assertThat(entity.getRetryCount()).isEqualTo(2);
    }
}

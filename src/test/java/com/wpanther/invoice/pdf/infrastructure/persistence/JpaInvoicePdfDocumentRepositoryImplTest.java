package com.wpanther.invoice.pdf.infrastructure.persistence;

import com.wpanther.invoice.pdf.domain.model.GenerationStatus;
import com.wpanther.invoice.pdf.domain.model.InvoicePdfDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JpaInvoicePdfDocumentRepositoryImplTest {

    @Mock
    private JpaInvoicePdfDocumentRepository jpaRepository;

    @InjectMocks
    private JpaInvoicePdfDocumentRepositoryImpl repository;

    private UUID id;
    private InvoicePdfDocument domain;
    private InvoicePdfDocumentEntity entity;

    @BeforeEach
    void setUp() {
        id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();

        domain = InvoicePdfDocument.builder()
                .id(id)
                .invoiceId("inv-123")
                .invoiceNumber("INV-2024-001")
                .documentPath("2024/01/15/invoice-INV-2024-001-abc.pdf")
                .documentUrl("http://localhost:9000/invoices/2024/01/15/invoice-INV-2024-001-abc.pdf")
                .fileSize(12345L)
                .mimeType("application/pdf")
                .xmlEmbedded(true)
                .status(GenerationStatus.COMPLETED)
                .retryCount(1)
                .createdAt(now)
                .completedAt(now)
                .build();

        entity = InvoicePdfDocumentEntity.builder()
                .id(id)
                .invoiceId("inv-123")
                .invoiceNumber("INV-2024-001")
                .documentPath("2024/01/15/invoice-INV-2024-001-abc.pdf")
                .documentUrl("http://localhost:9000/invoices/2024/01/15/invoice-INV-2024-001-abc.pdf")
                .fileSize(12345L)
                .mimeType("application/pdf")
                .xmlEmbedded(true)
                .status(GenerationStatus.COMPLETED)
                .errorMessage(null)
                .retryCount(1)
                .createdAt(now)
                .completedAt(now)
                .build();
    }

    // -------------------------------------------------------------------------
    // toEntity mapping
    // -------------------------------------------------------------------------

    @Test
    void save_mapsAllDomainFieldsToEntity() {
        when(jpaRepository.save(any())).thenReturn(entity);

        repository.save(domain);

        ArgumentCaptor<InvoicePdfDocumentEntity> captor =
                ArgumentCaptor.forClass(InvoicePdfDocumentEntity.class);
        verify(jpaRepository).save(captor.capture());

        InvoicePdfDocumentEntity saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(id);
        assertThat(saved.getInvoiceId()).isEqualTo("inv-123");
        assertThat(saved.getInvoiceNumber()).isEqualTo("INV-2024-001");
        assertThat(saved.getDocumentPath()).isEqualTo("2024/01/15/invoice-INV-2024-001-abc.pdf");
        assertThat(saved.getDocumentUrl()).contains("invoice-INV-2024-001-abc.pdf");
        assertThat(saved.getFileSize()).isEqualTo(12345L);
        assertThat(saved.getMimeType()).isEqualTo("application/pdf");
        assertThat(saved.getXmlEmbedded()).isTrue();
        assertThat(saved.getStatus()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(saved.getRetryCount()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // toDomain mapping
    // -------------------------------------------------------------------------

    @Test
    void save_mapsAllEntityFieldsToDomain() {
        when(jpaRepository.save(any())).thenReturn(entity);

        InvoicePdfDocument result = repository.save(domain);

        assertThat(result.getId()).isEqualTo(id);
        assertThat(result.getInvoiceId()).isEqualTo("inv-123");
        assertThat(result.getInvoiceNumber()).isEqualTo("INV-2024-001");
        assertThat(result.getDocumentPath()).isEqualTo("2024/01/15/invoice-INV-2024-001-abc.pdf");
        assertThat(result.getDocumentUrl()).contains("invoice-INV-2024-001-abc.pdf");
        assertThat(result.getFileSize()).isEqualTo(12345L);
        assertThat(result.getMimeType()).isEqualTo("application/pdf");
        assertThat(result.isXmlEmbedded()).isTrue();
        assertThat(result.getStatus()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(result.getRetryCount()).isEqualTo(1);
    }

    @Test
    void toDomain_nullFileSize_defaultsToZero() {
        entity.setFileSize(null);
        when(jpaRepository.save(any())).thenReturn(entity);

        InvoicePdfDocument result = repository.save(domain);

        assertThat(result.getFileSize()).isZero();
    }

    @Test
    void toDomain_nullXmlEmbedded_defaultsToFalse() {
        entity.setXmlEmbedded(null);
        when(jpaRepository.save(any())).thenReturn(entity);

        InvoicePdfDocument result = repository.save(domain);

        assertThat(result.isXmlEmbedded()).isFalse();
    }

    @Test
    void toDomain_nullRetryCount_defaultsToZero() {
        entity.setRetryCount(null);
        when(jpaRepository.save(any())).thenReturn(entity);

        InvoicePdfDocument result = repository.save(domain);

        assertThat(result.getRetryCount()).isZero();
    }

    // -------------------------------------------------------------------------
    // findById
    // -------------------------------------------------------------------------

    @Test
    void findById_found_returnsMappedDomain() {
        when(jpaRepository.findById(id)).thenReturn(Optional.of(entity));

        Optional<InvoicePdfDocument> result = repository.findById(id);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(id);
    }

    @Test
    void findById_notFound_returnsEmpty() {
        when(jpaRepository.findById(id)).thenReturn(Optional.empty());

        Optional<InvoicePdfDocument> result = repository.findById(id);

        assertThat(result).isEmpty();
    }

    // -------------------------------------------------------------------------
    // findByInvoiceId
    // -------------------------------------------------------------------------

    @Test
    void findByInvoiceId_found_returnsMappedDomain() {
        when(jpaRepository.findByInvoiceId("inv-123")).thenReturn(Optional.of(entity));

        Optional<InvoicePdfDocument> result = repository.findByInvoiceId("inv-123");

        assertThat(result).isPresent();
        assertThat(result.get().getInvoiceId()).isEqualTo("inv-123");
    }

    @Test
    void findByInvoiceId_notFound_returnsEmpty() {
        when(jpaRepository.findByInvoiceId("unknown")).thenReturn(Optional.empty());

        Optional<InvoicePdfDocument> result = repository.findByInvoiceId("unknown");

        assertThat(result).isEmpty();
    }

    // -------------------------------------------------------------------------
    // deleteById
    // -------------------------------------------------------------------------

    @Test
    void deleteById_delegatesToJpaRepository() {
        repository.deleteById(id);

        verify(jpaRepository).deleteById(id);
    }

    // -------------------------------------------------------------------------
    // flush
    // -------------------------------------------------------------------------

    @Test
    void flush_delegatesToJpaRepository() {
        repository.flush();

        verify(jpaRepository).flush();
    }
}

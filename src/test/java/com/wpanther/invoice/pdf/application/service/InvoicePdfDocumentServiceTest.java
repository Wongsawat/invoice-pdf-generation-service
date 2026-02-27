package com.wpanther.invoice.pdf.application.service;

import com.wpanther.invoice.pdf.application.port.out.PdfEventPort;
import com.wpanther.invoice.pdf.application.port.out.SagaReplyPort;
import com.wpanther.invoice.pdf.domain.event.CompensateInvoicePdfCommand;
import com.wpanther.invoice.pdf.domain.event.InvoicePdfGeneratedEvent;
import com.wpanther.invoice.pdf.domain.event.ProcessInvoicePdfCommand;
import com.wpanther.invoice.pdf.domain.model.GenerationStatus;
import com.wpanther.invoice.pdf.domain.model.InvoicePdfDocument;
import com.wpanther.invoice.pdf.domain.repository.InvoicePdfDocumentRepository;
import com.wpanther.saga.domain.enums.SagaStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("InvoicePdfDocumentService Unit Tests")
class InvoicePdfDocumentServiceTest {

    @Mock private InvoicePdfDocumentRepository repository;
    @Mock private PdfEventPort pdfEventPort;
    @Mock private SagaReplyPort sagaReplyPort;

    @InjectMocks
    private InvoicePdfDocumentService service;

    private static final UUID DOC_ID = UUID.randomUUID();
    private static final String S3_KEY  = "2024/01/15/invoice-INV-001-uuid.pdf";
    private static final String FILE_URL = "http://localhost:9001/invoices/" + S3_KEY;

    private ProcessInvoicePdfCommand processCommand() {
        return new ProcessInvoicePdfCommand(
                "saga-001", SagaStep.GENERATE_INVOICE_PDF, "corr-456",
                "doc-123", "inv-001", "INV-001",
                "http://minio/signed.xml", "{}");
    }

    private CompensateInvoicePdfCommand compensateCommand() {
        return new CompensateInvoicePdfCommand(
                "saga-001", SagaStep.GENERATE_INVOICE_PDF, "corr-456",
                "doc-123", "inv-001");
    }

    private InvoicePdfDocument generatingDoc() {
        return InvoicePdfDocument.builder()
                .id(DOC_ID).invoiceId("inv-001").invoiceNumber("INV-001")
                .status(GenerationStatus.GENERATING)
                .retryCount(0)
                .build();
    }

    // -------------------------------------------------------------------------
    // findByInvoiceId
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("findByInvoiceId() delegates to repository and returns the result")
    void findByInvoiceId_delegatesToRepository() {
        InvoicePdfDocument doc = generatingDoc();
        when(repository.findByInvoiceId("inv-001")).thenReturn(Optional.of(doc));

        Optional<InvoicePdfDocument> result = service.findByInvoiceId("inv-001");

        assertThat(result).isPresent().contains(doc);
        verify(repository).findByInvoiceId("inv-001");
    }

    @Test
    @DisplayName("findByInvoiceId() returns empty when no document exists")
    void findByInvoiceId_returnsEmpty_whenNotFound() {
        when(repository.findByInvoiceId("unknown")).thenReturn(Optional.empty());

        Optional<InvoicePdfDocument> result = service.findByInvoiceId("unknown");

        assertThat(result).isEmpty();
    }

    // -------------------------------------------------------------------------
    // beginGeneration
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("beginGeneration() creates GENERATING document with single save")
    void beginGeneration_savesOnce_inGeneratingState() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InvoicePdfDocument result = service.beginGeneration("inv-001", "INV-001");

        assertThat(result.getStatus()).isEqualTo(GenerationStatus.GENERATING);
        assertThat(result.getInvoiceId()).isEqualTo("inv-001");
        verify(repository, times(1)).save(any());
    }

    // -------------------------------------------------------------------------
    // completeGenerationAndPublish
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("completeGenerationAndPublish() marks COMPLETED and publishes both events")
    void completeGenerationAndPublish_marksCompletedAndPublishes() {
        InvoicePdfDocument doc = generatingDoc();
        when(repository.findById(DOC_ID)).thenReturn(Optional.of(doc));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.completeGenerationAndPublish(DOC_ID, S3_KEY, FILE_URL, 5000L, -1, processCommand());

        assertThat(doc.getStatus()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(doc.getDocumentPath()).isEqualTo(S3_KEY);
        assertThat(doc.getFileSize()).isEqualTo(5000L);
        assertThat(doc.isXmlEmbedded()).isTrue();
        verify(pdfEventPort).publishPdfGenerated(any(InvoicePdfGeneratedEvent.class));
        verify(sagaReplyPort).publishSuccess(eq("saga-001"), eq(SagaStep.GENERATE_INVOICE_PDF),
                eq("corr-456"), eq(FILE_URL), eq(5000L));
    }

    @Test
    @DisplayName("completeGenerationAndPublish() carries forward retry count when previousRetryCount >= 0")
    void completeGenerationAndPublish_carriesForwardRetryCount() {
        InvoicePdfDocument doc = generatingDoc();
        when(repository.findById(DOC_ID)).thenReturn(Optional.of(doc));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.completeGenerationAndPublish(DOC_ID, S3_KEY, FILE_URL, 1000L, 1, processCommand());

        // previousRetryCount=1 → target=2
        assertThat(doc.getRetryCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("completeGenerationAndPublish() does not change retryCount when previousRetryCount is -1")
    void completeGenerationAndPublish_noRetryCountWhenFirstAttempt() {
        InvoicePdfDocument doc = generatingDoc();
        when(repository.findById(DOC_ID)).thenReturn(Optional.of(doc));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.completeGenerationAndPublish(DOC_ID, S3_KEY, FILE_URL, 1000L, -1, processCommand());

        assertThat(doc.getRetryCount()).isZero();
    }

    // -------------------------------------------------------------------------
    // failGenerationAndPublish
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("failGenerationAndPublish() marks FAILED, persists retry count, publishes FAILURE")
    void failGenerationAndPublish_marksFailedAndPublishes() {
        InvoicePdfDocument doc = generatingDoc();
        when(repository.findById(DOC_ID)).thenReturn(Optional.of(doc));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.failGenerationAndPublish(DOC_ID, "FOP failed", 1, processCommand());

        assertThat(doc.getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(doc.getRetryCount()).isEqualTo(2); // 1+1
        verify(sagaReplyPort).publishFailure(eq("saga-001"), eq(SagaStep.GENERATE_INVOICE_PDF),
                eq("corr-456"), eq("FOP failed"));
        verify(pdfEventPort, never()).publishPdfGenerated(any());
    }

    // -------------------------------------------------------------------------
    // Reply publishers
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("publishIdempotentSuccess() publishes both events without touching repository")
    void publishIdempotentSuccess_publishesWithoutSave() {
        InvoicePdfDocument existing = InvoicePdfDocument.builder()
                .id(DOC_ID).invoiceId("inv-001").invoiceNumber("INV-001")
                .status(GenerationStatus.COMPLETED)
                .documentUrl(FILE_URL).fileSize(9000L).xmlEmbedded(true)
                .build();

        service.publishIdempotentSuccess(existing, processCommand());

        verify(pdfEventPort).publishPdfGenerated(any());
        verify(sagaReplyPort).publishSuccess(eq("saga-001"), any(), eq("corr-456"),
                eq(FILE_URL), eq(9000L));
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("publishRetryExhausted() publishes FAILURE reply without touching repository")
    void publishRetryExhausted_publishesFailure() {
        service.publishRetryExhausted(processCommand());

        verify(sagaReplyPort).publishFailure(eq("saga-001"), any(), eq("corr-456"),
                contains("Maximum retry attempts exceeded"));
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("publishGenerationFailure() publishes FAILURE reply")
    void publishGenerationFailure_publishesFailure() {
        service.publishGenerationFailure(processCommand(), "signedXmlUrl is null");

        verify(sagaReplyPort).publishFailure(eq("saga-001"), any(), eq("corr-456"),
                eq("signedXmlUrl is null"));
    }

    @Test
    @DisplayName("publishCompensated() publishes COMPENSATED reply")
    void publishCompensated_publishes() {
        service.publishCompensated(compensateCommand());

        verify(sagaReplyPort).publishCompensated(eq("saga-001"), any(), eq("corr-456"));
    }

    @Test
    @DisplayName("publishCompensationFailure() publishes FAILURE reply")
    void publishCompensationFailure_publishes() {
        service.publishCompensationFailure(compensateCommand(), "Compensation failed: S3 error");

        verify(sagaReplyPort).publishFailure(eq("saga-001"), any(), eq("corr-456"),
                eq("Compensation failed: S3 error"));
    }

    @Test
    @DisplayName("completeGenerationAndPublish() throws IllegalStateException when document not found")
    void completeGenerationAndPublish_documentNotFound_throwsIllegalStateException() {
        when(repository.findById(DOC_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.completeGenerationAndPublish(DOC_ID, S3_KEY, FILE_URL, 1000L, -1, processCommand()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("internal state error");
    }

    @Test
    @DisplayName("failGenerationAndPublish() uses fallback message when errorMessage is null")
    void failGenerationAndPublish_nullErrorMessage_usesFallback() {
        InvoicePdfDocument doc = generatingDoc();
        when(repository.findById(DOC_ID)).thenReturn(Optional.of(doc));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.failGenerationAndPublish(DOC_ID, null, -1, processCommand());

        assertThat(doc.getErrorMessage()).isEqualTo("PDF generation failed");
        verify(sagaReplyPort).publishFailure(eq("saga-001"), any(), eq("corr-456"),
                eq("PDF generation failed"));
    }

    // -------------------------------------------------------------------------
    // deleteById
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("deleteById() deletes and flushes")
    void deleteById_deletesAndFlushes() {
        service.deleteById(DOC_ID);

        verify(repository).deleteById(DOC_ID);
        verify(repository).flush();
    }
}

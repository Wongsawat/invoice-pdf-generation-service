package com.wpanther.invoice.pdf.application.service;

import com.wpanther.invoice.pdf.application.port.out.PdfStoragePort;
import com.wpanther.invoice.pdf.application.port.out.SagaReplyPort;
import com.wpanther.invoice.pdf.domain.event.CompensateInvoicePdfCommand;
import com.wpanther.invoice.pdf.domain.event.ProcessInvoicePdfCommand;
import com.wpanther.invoice.pdf.domain.model.GenerationStatus;
import com.wpanther.invoice.pdf.domain.model.InvoicePdfDocument;
import com.wpanther.invoice.pdf.domain.repository.InvoicePdfDocumentRepository;
import com.wpanther.invoice.pdf.domain.service.InvoicePdfGenerationService;
import com.wpanther.saga.domain.enums.SagaStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SagaCommandHandler Unit Tests")
class SagaCommandHandlerTest {

    @Mock private InvoicePdfDocumentRepository repository;
    @Mock private InvoicePdfDocumentService pdfDocumentService;
    @Mock private InvoicePdfGenerationService pdfGenerationService;
    @Mock private PdfStoragePort pdfStoragePort;
    @Mock private SagaReplyPort sagaReplyPort;
    @Mock private org.springframework.web.client.RestTemplate restTemplate;

    private SagaCommandHandler sagaCommandHandler;

    private static final String SIGNED_XML_URL     = "http://minio:9000/signed/invoice-signed.xml";
    private static final String SIGNED_XML_CONTENT = "<Invoice>signed</Invoice>";
    private static final String S3_KEY             = "2024/01/15/invoice-INV-2024-001-uuid.pdf";
    private static final String FILE_URL           = "http://localhost:9001/invoices/" + S3_KEY;

    @BeforeEach
    void setUp() {
        sagaCommandHandler = new SagaCommandHandler(
                repository, pdfDocumentService, pdfGenerationService,
                pdfStoragePort, sagaReplyPort, restTemplate, 3);
    }

    private ProcessInvoicePdfCommand processCommand() {
        return new ProcessInvoicePdfCommand(
                "saga-001", SagaStep.GENERATE_INVOICE_PDF, "corr-456",
                "doc-123", "inv-001", "INV-2024-001",
                SIGNED_XML_URL, "{}");
    }

    private CompensateInvoicePdfCommand compensateCommand() {
        return new CompensateInvoicePdfCommand(
                "saga-001", SagaStep.GENERATE_INVOICE_PDF, "corr-456",
                "doc-123", "inv-001");
    }

    private InvoicePdfDocument generatingDoc() {
        return InvoicePdfDocument.builder()
                .id(UUID.randomUUID()).invoiceId("inv-001").invoiceNumber("INV-2024-001")
                .status(GenerationStatus.GENERATING).retryCount(0)
                .build();
    }

    private InvoicePdfDocument completedDoc() {
        return InvoicePdfDocument.builder()
                .id(UUID.randomUUID()).invoiceId("inv-001").invoiceNumber("INV-2024-001")
                .status(GenerationStatus.COMPLETED)
                .documentPath(S3_KEY).documentUrl(FILE_URL).fileSize(12345L)
                .xmlEmbedded(true).retryCount(0)
                .build();
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Happy path: beginGeneration → generate → upload → completeGenerationAndPublish")
    void handleProcessCommand_success() throws Exception {
        byte[] pdfBytes = new byte[5000];
        InvoicePdfDocument doc = generatingDoc();

        when(repository.findByInvoiceId("inv-001")).thenReturn(Optional.empty());
        when(restTemplate.getForObject(SIGNED_XML_URL, String.class)).thenReturn(SIGNED_XML_CONTENT);
        when(pdfGenerationService.generatePdf(anyString(), anyString(), anyString())).thenReturn(pdfBytes);
        when(pdfStoragePort.store(anyString(), any())).thenReturn(S3_KEY);
        when(pdfStoragePort.resolveUrl(S3_KEY)).thenReturn(FILE_URL);
        when(pdfDocumentService.beginGeneration("inv-001", "INV-2024-001")).thenReturn(doc);

        sagaCommandHandler.handleProcessCommand(processCommand());

        verify(pdfDocumentService).beginGeneration("inv-001", "INV-2024-001");
        verify(pdfGenerationService).generatePdf("INV-2024-001", SIGNED_XML_CONTENT, "{}");
        verify(pdfStoragePort).store("INV-2024-001", pdfBytes);
        verify(pdfDocumentService).completeGenerationAndPublish(
                eq(doc.getId()), eq(S3_KEY), eq(FILE_URL), eq(5000L), eq(-1), any());
    }

    // -------------------------------------------------------------------------
    // Idempotency
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Idempotency: already COMPLETED → publishIdempotentSuccess, no generation")
    void handleProcessCommand_alreadyCompleted() throws Exception {
        when(repository.findByInvoiceId("inv-001")).thenReturn(Optional.of(completedDoc()));

        sagaCommandHandler.handleProcessCommand(processCommand());

        verify(pdfDocumentService).publishIdempotentSuccess(any(), any());
        verify(pdfDocumentService, never()).beginGeneration(anyString(), anyString());
        verifyNoInteractions(pdfGenerationService, pdfStoragePort);
    }

    // -------------------------------------------------------------------------
    // Retry logic
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Max retries exceeded → publishRetryExhausted, no generation")
    void handleProcessCommand_maxRetriesExceeded() {
        InvoicePdfDocument failed = InvoicePdfDocument.builder()
                .id(UUID.randomUUID()).invoiceId("inv-001").invoiceNumber("INV-2024-001")
                .status(GenerationStatus.FAILED).retryCount(3).build();
        when(repository.findByInvoiceId("inv-001")).thenReturn(Optional.of(failed));

        sagaCommandHandler.handleProcessCommand(processCommand());

        verify(pdfDocumentService).publishRetryExhausted(any());
        verify(pdfDocumentService, never()).beginGeneration(anyString(), anyString());
    }

    @Test
    @DisplayName("Retry below max: deleteById + beginGeneration + completeGenerationAndPublish")
    void handleProcessCommand_retryBelowMax() throws Exception {
        byte[] pdfBytes = new byte[1000];
        UUID failedId = UUID.randomUUID();
        InvoicePdfDocument failed = InvoicePdfDocument.builder()
                .id(failedId).invoiceId("inv-001").invoiceNumber("INV-2024-001")
                .status(GenerationStatus.FAILED).retryCount(1).build();
        InvoicePdfDocument newDoc = generatingDoc();

        when(repository.findByInvoiceId("inv-001")).thenReturn(Optional.of(failed));
        when(restTemplate.getForObject(SIGNED_XML_URL, String.class)).thenReturn(SIGNED_XML_CONTENT);
        when(pdfGenerationService.generatePdf(anyString(), anyString(), anyString())).thenReturn(pdfBytes);
        when(pdfStoragePort.store(anyString(), any())).thenReturn(S3_KEY);
        when(pdfStoragePort.resolveUrl(S3_KEY)).thenReturn(FILE_URL);
        when(pdfDocumentService.beginGeneration(anyString(), anyString())).thenReturn(newDoc);

        sagaCommandHandler.handleProcessCommand(processCommand());

        verify(pdfDocumentService).deleteById(failedId);
        verify(pdfDocumentService).beginGeneration("inv-001", "INV-2024-001");
        // previousRetryCount should be carried forward (failed.retryCount = 1)
        verify(pdfDocumentService).completeGenerationAndPublish(
                eq(newDoc.getId()), any(), any(), anyLong(), eq(1), any());
    }

    @Test
    @DisplayName("Retry carry-forward: previousRetryCount passed correctly to completeGenerationAndPublish")
    void handleProcessCommand_retryCountCarriedForward() throws Exception {
        byte[] pdfBytes = new byte[1000];
        InvoicePdfDocument failed = InvoicePdfDocument.builder()
                .id(UUID.randomUUID()).invoiceId("inv-001").invoiceNumber("INV-2024-001")
                .status(GenerationStatus.FAILED).retryCount(2).build();
        InvoicePdfDocument newDoc = generatingDoc();

        when(repository.findByInvoiceId("inv-001")).thenReturn(Optional.of(failed));
        when(restTemplate.getForObject(SIGNED_XML_URL, String.class)).thenReturn(SIGNED_XML_CONTENT);
        when(pdfGenerationService.generatePdf(anyString(), anyString(), anyString())).thenReturn(pdfBytes);
        when(pdfStoragePort.store(anyString(), any())).thenReturn(S3_KEY);
        when(pdfStoragePort.resolveUrl(S3_KEY)).thenReturn(FILE_URL);
        when(pdfDocumentService.beginGeneration(anyString(), anyString())).thenReturn(newDoc);

        sagaCommandHandler.handleProcessCommand(processCommand());

        // previousRetryCount = 2 (from failed doc)
        verify(pdfDocumentService).completeGenerationAndPublish(
                any(), any(), any(), anyLong(), eq(2), any());
    }

    // -------------------------------------------------------------------------
    // Failure paths
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Null signed XML → publishGenerationFailure, no beginGeneration")
    void handleProcessCommand_nullSignedXml() {
        when(repository.findByInvoiceId("inv-001")).thenReturn(Optional.empty());
        when(restTemplate.getForObject(SIGNED_XML_URL, String.class)).thenReturn(null);
        when(pdfDocumentService.beginGeneration(anyString(), anyString())).thenReturn(generatingDoc());

        sagaCommandHandler.handleProcessCommand(processCommand());

        verify(pdfDocumentService).failGenerationAndPublish(any(), contains("Failed to download signed XML"), anyInt(), any());
    }

    @Test
    @DisplayName("Blank signedXmlUrl → publishGenerationFailure before beginGeneration")
    void handleProcessCommand_blankSignedXmlUrl() {
        ProcessInvoicePdfCommand cmd = new ProcessInvoicePdfCommand(
                "saga-001", SagaStep.GENERATE_INVOICE_PDF, "corr-456",
                "doc-123", "inv-001", "INV-2024-001", "  ", "{}");
        when(repository.findByInvoiceId("inv-001")).thenReturn(Optional.empty());

        sagaCommandHandler.handleProcessCommand(cmd);

        verify(pdfDocumentService).publishGenerationFailure(any(), contains("signedXmlUrl"));
        verify(pdfDocumentService, never()).beginGeneration(anyString(), anyString());
    }

    @Test
    @DisplayName("RestTemplate throws → failGenerationAndPublish called")
    void handleProcessCommand_restTemplateFails() {
        InvoicePdfDocument doc = generatingDoc();
        when(repository.findByInvoiceId("inv-001")).thenReturn(Optional.empty());
        when(restTemplate.getForObject(SIGNED_XML_URL, String.class))
                .thenThrow(new RuntimeException("Connection refused"));
        when(pdfDocumentService.beginGeneration(anyString(), anyString())).thenReturn(doc);

        sagaCommandHandler.handleProcessCommand(processCommand());

        verify(pdfDocumentService).failGenerationAndPublish(
                eq(doc.getId()), contains("RuntimeException"), eq(-1), any());
    }

    @Test
    @DisplayName("PDF generation throws → failGenerationAndPublish called")
    void handleProcessCommand_pdfGenerationFails() throws Exception {
        InvoicePdfDocument doc = generatingDoc();
        when(repository.findByInvoiceId("inv-001")).thenReturn(Optional.empty());
        when(restTemplate.getForObject(SIGNED_XML_URL, String.class)).thenReturn(SIGNED_XML_CONTENT);
        when(pdfGenerationService.generatePdf(anyString(), anyString(), anyString()))
                .thenThrow(new InvoicePdfGenerationService.InvoicePdfGenerationException("FOP failed"));
        when(pdfDocumentService.beginGeneration(anyString(), anyString())).thenReturn(doc);

        sagaCommandHandler.handleProcessCommand(processCommand());

        verify(pdfDocumentService).failGenerationAndPublish(
                eq(doc.getId()), contains("FOP failed"), eq(-1), any());
        verify(pdfDocumentService, never()).completeGenerationAndPublish(any(), any(), any(), anyLong(), anyInt(), any());
    }

    @Test
    @DisplayName("MinIO upload throws → failGenerationAndPublish called")
    void handleProcessCommand_minioUploadFails() throws Exception {
        InvoicePdfDocument doc = generatingDoc();
        when(repository.findByInvoiceId("inv-001")).thenReturn(Optional.empty());
        when(restTemplate.getForObject(SIGNED_XML_URL, String.class)).thenReturn(SIGNED_XML_CONTENT);
        when(pdfGenerationService.generatePdf(anyString(), anyString(), anyString())).thenReturn(new byte[1000]);
        when(pdfStoragePort.store(anyString(), any())).thenThrow(new RuntimeException("MinIO unavailable"));
        when(pdfDocumentService.beginGeneration(anyString(), anyString())).thenReturn(doc);

        sagaCommandHandler.handleProcessCommand(processCommand());

        verify(pdfDocumentService).failGenerationAndPublish(
                eq(doc.getId()), contains("MinIO"), eq(-1), any());
    }

    // -------------------------------------------------------------------------
    // handleCompensation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Compensation: deleteById + pdfStoragePort.delete + publishCompensated")
    void handleCompensation_success() {
        InvoicePdfDocument doc = completedDoc();
        when(repository.findByInvoiceId("inv-001")).thenReturn(Optional.of(doc));

        sagaCommandHandler.handleCompensation(compensateCommand());

        verify(pdfDocumentService).deleteById(doc.getId());
        verify(pdfStoragePort).delete(doc.getDocumentPath());
        verify(pdfDocumentService).publishCompensated(any());
    }

    @Test
    @DisplayName("Compensation with no document → publishCompensated only (idempotent)")
    void handleCompensation_noDocument() {
        when(repository.findByInvoiceId("inv-001")).thenReturn(Optional.empty());

        sagaCommandHandler.handleCompensation(compensateCommand());

        verify(pdfDocumentService, never()).deleteById(any());
        verifyNoInteractions(pdfStoragePort);
        verify(pdfDocumentService).publishCompensated(any());
    }

    @Test
    @DisplayName("MinIO delete failure during compensation is swallowed; publishCompensated still called")
    void handleCompensation_minioDeleteFails_stillPublishesCompensated() {
        InvoicePdfDocument doc = completedDoc();
        when(repository.findByInvoiceId("inv-001")).thenReturn(Optional.of(doc));
        doThrow(new RuntimeException("MinIO error")).when(pdfStoragePort).delete(anyString());

        sagaCommandHandler.handleCompensation(compensateCommand());

        verify(pdfDocumentService).deleteById(doc.getId());
        verify(pdfDocumentService).publishCompensated(any());
    }

    @Test
    @DisplayName("deleteById throws during compensation → publishCompensationFailure")
    void handleCompensation_dbDeleteFails() {
        InvoicePdfDocument doc = completedDoc();
        when(repository.findByInvoiceId("inv-001")).thenReturn(Optional.of(doc));
        doThrow(new RuntimeException("DB error")).when(pdfDocumentService).deleteById(any());

        sagaCommandHandler.handleCompensation(compensateCommand());

        verify(pdfDocumentService).publishCompensationFailure(any(), contains("Compensation failed"));
        verify(pdfDocumentService, never()).publishCompensated(any());
    }
}

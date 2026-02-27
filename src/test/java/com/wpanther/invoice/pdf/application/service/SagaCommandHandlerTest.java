package com.wpanther.invoice.pdf.application.service;

import com.wpanther.invoice.pdf.application.port.out.PdfStoragePort;
import com.wpanther.invoice.pdf.application.port.out.SagaReplyPort;
import com.wpanther.invoice.pdf.application.port.out.SignedXmlFetchPort;
import com.wpanther.invoice.pdf.domain.event.CompensateInvoicePdfCommand;
import com.wpanther.invoice.pdf.domain.event.ProcessInvoicePdfCommand;
import com.wpanther.invoice.pdf.domain.model.GenerationStatus;
import com.wpanther.invoice.pdf.domain.model.InvoicePdfDocument;
import com.wpanther.invoice.pdf.domain.service.InvoicePdfGenerationService;
import com.wpanther.saga.domain.enums.SagaStep;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SagaCommandHandler Unit Tests")
class SagaCommandHandlerTest {

    @Mock private InvoicePdfDocumentService pdfDocumentService;
    @Mock private InvoicePdfGenerationService pdfGenerationService;
    @Mock private PdfStoragePort pdfStoragePort;
    @Mock private SagaReplyPort sagaReplyPort;
    @Mock private SignedXmlFetchPort signedXmlFetchPort;

    private SagaCommandHandler sagaCommandHandler;

    private static final String SIGNED_XML_URL     = "http://minio:9000/signed/invoice-signed.xml";
    private static final String SIGNED_XML_CONTENT = "<Invoice>signed</Invoice>";
    private static final String S3_KEY             = "2024/01/15/invoice-INV-2024-001-uuid.pdf";
    private static final String FILE_URL           = "http://localhost:9001/invoices/" + S3_KEY;

    @BeforeEach
    void setUp() {
        sagaCommandHandler = new SagaCommandHandler(
                pdfDocumentService, pdfGenerationService,
                pdfStoragePort, sagaReplyPort, signedXmlFetchPort, 3);
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
    @DisplayName("Happy path: beginGeneration → fetch → generate → upload → completeGenerationAndPublish")
    void handleProcessCommand_success() throws Exception {
        byte[] pdfBytes = new byte[5000];
        InvoicePdfDocument doc = generatingDoc();

        when(pdfDocumentService.findByInvoiceId("inv-001")).thenReturn(Optional.empty());
        when(signedXmlFetchPort.fetch(SIGNED_XML_URL)).thenReturn(SIGNED_XML_CONTENT);
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
        when(pdfDocumentService.findByInvoiceId("inv-001")).thenReturn(Optional.of(completedDoc()));

        sagaCommandHandler.handleProcessCommand(processCommand());

        verify(pdfDocumentService).publishIdempotentSuccess(any(), any());
        verify(pdfDocumentService, never()).beginGeneration(anyString(), anyString());
        verifyNoInteractions(pdfGenerationService, pdfStoragePort, signedXmlFetchPort);
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
        when(pdfDocumentService.findByInvoiceId("inv-001")).thenReturn(Optional.of(failed));

        sagaCommandHandler.handleProcessCommand(processCommand());

        verify(pdfDocumentService).publishRetryExhausted(any());
        verify(pdfDocumentService, never()).beginGeneration(anyString(), anyString());
        verifyNoInteractions(signedXmlFetchPort);
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

        when(pdfDocumentService.findByInvoiceId("inv-001")).thenReturn(Optional.of(failed));
        when(signedXmlFetchPort.fetch(SIGNED_XML_URL)).thenReturn(SIGNED_XML_CONTENT);
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

        when(pdfDocumentService.findByInvoiceId("inv-001")).thenReturn(Optional.of(failed));
        when(signedXmlFetchPort.fetch(SIGNED_XML_URL)).thenReturn(SIGNED_XML_CONTENT);
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
    // Stuck GENERATING state (TX2 rolled back)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Stuck GENERATING (TX2 rolled back), retries below max → deleteById + beginGeneration + retry")
    void handleProcessCommand_stuckGenerating_retriesNotExceeded() throws Exception {
        byte[] pdfBytes = new byte[1000];
        UUID stuckId = UUID.randomUUID();
        InvoicePdfDocument stuck = InvoicePdfDocument.builder()
                .id(stuckId).invoiceId("inv-001").invoiceNumber("INV-2024-001")
                .status(GenerationStatus.GENERATING).retryCount(1).build();
        InvoicePdfDocument newDoc = generatingDoc();

        when(pdfDocumentService.findByInvoiceId("inv-001")).thenReturn(Optional.of(stuck));
        when(signedXmlFetchPort.fetch(SIGNED_XML_URL)).thenReturn(SIGNED_XML_CONTENT);
        when(pdfGenerationService.generatePdf(anyString(), anyString(), anyString())).thenReturn(pdfBytes);
        when(pdfStoragePort.store(anyString(), any())).thenReturn(S3_KEY);
        when(pdfStoragePort.resolveUrl(S3_KEY)).thenReturn(FILE_URL);
        when(pdfDocumentService.beginGeneration(anyString(), anyString())).thenReturn(newDoc);

        sagaCommandHandler.handleProcessCommand(processCommand());

        verify(pdfDocumentService).deleteById(stuckId);
        verify(pdfDocumentService).beginGeneration("inv-001", "INV-2024-001");
        // previousRetryCount carried forward from the stuck document (retryCount = 1)
        verify(pdfDocumentService).completeGenerationAndPublish(
                eq(newDoc.getId()), any(), any(), anyLong(), eq(1), any());
        verify(pdfDocumentService, never()).publishRetryExhausted(any());
    }

    @Test
    @DisplayName("Stuck GENERATING with max retries exceeded → publishRetryExhausted, no generation")
    void handleProcessCommand_stuckGenerating_maxRetriesExceeded() {
        InvoicePdfDocument stuck = InvoicePdfDocument.builder()
                .id(UUID.randomUUID()).invoiceId("inv-001").invoiceNumber("INV-2024-001")
                .status(GenerationStatus.GENERATING).retryCount(3).build();
        when(pdfDocumentService.findByInvoiceId("inv-001")).thenReturn(Optional.of(stuck));

        sagaCommandHandler.handleProcessCommand(processCommand());

        verify(pdfDocumentService).publishRetryExhausted(any());
        verify(pdfDocumentService, never()).deleteById(any());
        verify(pdfDocumentService, never()).beginGeneration(anyString(), anyString());
        verifyNoInteractions(signedXmlFetchPort);
    }

    // -------------------------------------------------------------------------
    // Failure paths
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Fetch returns blank content → failGenerationAndPublish after beginGeneration")
    void handleProcessCommand_blankFetchedXml() {
        when(pdfDocumentService.findByInvoiceId("inv-001")).thenReturn(Optional.empty());
        when(pdfDocumentService.beginGeneration(anyString(), anyString())).thenReturn(generatingDoc());
        when(signedXmlFetchPort.fetch(SIGNED_XML_URL))
                .thenThrow(new SignedXmlFetchPort.SignedXmlFetchException(
                        "Failed to download signed XML from " + SIGNED_XML_URL));

        sagaCommandHandler.handleProcessCommand(processCommand());

        verify(pdfDocumentService).failGenerationAndPublish(
                any(), contains("Failed to download signed XML"), anyInt(), any());
    }

    @Test
    @DisplayName("Blank invoiceNumber → publishGenerationFailure before beginGeneration")
    void handleProcessCommand_blankInvoiceNumber() {
        ProcessInvoicePdfCommand cmd = new ProcessInvoicePdfCommand(
                "saga-001", SagaStep.GENERATE_INVOICE_PDF, "corr-456",
                "doc-123", "inv-001", "   ", SIGNED_XML_URL, "{}");

        sagaCommandHandler.handleProcessCommand(cmd);

        verify(pdfDocumentService).publishGenerationFailure(any(), contains("invoiceNumber"));
        verify(pdfDocumentService, never()).beginGeneration(anyString(), anyString());
        verifyNoInteractions(signedXmlFetchPort);
    }

    @Test
    @DisplayName("Blank signedXmlUrl → publishGenerationFailure before beginGeneration")
    void handleProcessCommand_blankSignedXmlUrl() {
        ProcessInvoicePdfCommand cmd = new ProcessInvoicePdfCommand(
                "saga-001", SagaStep.GENERATE_INVOICE_PDF, "corr-456",
                "doc-123", "inv-001", "INV-2024-001", "  ", "{}");

        sagaCommandHandler.handleProcessCommand(cmd);

        verify(pdfDocumentService).publishGenerationFailure(any(), contains("signedXmlUrl"));
        verify(pdfDocumentService, never()).beginGeneration(anyString(), anyString());
        verifyNoInteractions(signedXmlFetchPort);
    }

    @Test
    @DisplayName("HTTP fetch throws → failGenerationAndPublish called")
    void handleProcessCommand_fetchFails() {
        InvoicePdfDocument doc = generatingDoc();
        when(pdfDocumentService.findByInvoiceId("inv-001")).thenReturn(Optional.empty());
        when(signedXmlFetchPort.fetch(SIGNED_XML_URL))
                .thenThrow(new SignedXmlFetchPort.SignedXmlFetchException(
                        "Failed to download signed XML from " + SIGNED_XML_URL,
                        new RuntimeException("Connection refused")));
        when(pdfDocumentService.beginGeneration(anyString(), anyString())).thenReturn(doc);

        sagaCommandHandler.handleProcessCommand(processCommand());

        verify(pdfDocumentService).failGenerationAndPublish(
                eq(doc.getId()), contains("Failed to download signed XML"), eq(-1), any());
    }

    @Test
    @DisplayName("PDF generation throws → failGenerationAndPublish called")
    void handleProcessCommand_pdfGenerationFails() throws Exception {
        InvoicePdfDocument doc = generatingDoc();
        when(pdfDocumentService.findByInvoiceId("inv-001")).thenReturn(Optional.empty());
        when(signedXmlFetchPort.fetch(SIGNED_XML_URL)).thenReturn(SIGNED_XML_CONTENT);
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
        when(pdfDocumentService.findByInvoiceId("inv-001")).thenReturn(Optional.empty());
        when(signedXmlFetchPort.fetch(SIGNED_XML_URL)).thenReturn(SIGNED_XML_CONTENT);
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
        when(pdfDocumentService.findByInvoiceId("inv-001")).thenReturn(Optional.of(doc));

        sagaCommandHandler.handleCompensation(compensateCommand());

        verify(pdfDocumentService).deleteById(doc.getId());
        verify(pdfStoragePort).delete(doc.getDocumentPath());
        verify(pdfDocumentService).publishCompensated(any());
    }

    @Test
    @DisplayName("Compensation with no document → publishCompensated only (idempotent)")
    void handleCompensation_noDocument() {
        when(pdfDocumentService.findByInvoiceId("inv-001")).thenReturn(Optional.empty());

        sagaCommandHandler.handleCompensation(compensateCommand());

        verify(pdfDocumentService, never()).deleteById(any());
        verifyNoInteractions(pdfStoragePort);
        verify(pdfDocumentService).publishCompensated(any());
    }

    @Test
    @DisplayName("MinIO delete failure during compensation is swallowed; publishCompensated still called")
    void handleCompensation_minioDeleteFails_stillPublishesCompensated() {
        InvoicePdfDocument doc = completedDoc();
        when(pdfDocumentService.findByInvoiceId("inv-001")).thenReturn(Optional.of(doc));
        doThrow(new RuntimeException("MinIO error")).when(pdfStoragePort).delete(anyString());

        sagaCommandHandler.handleCompensation(compensateCommand());

        verify(pdfDocumentService).deleteById(doc.getId());
        verify(pdfDocumentService).publishCompensated(any());
    }

    // -------------------------------------------------------------------------
    // publishOrchestrationFailureForUnparsedMessage
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("publishOrchestrationFailureForUnparsedMessage: publishes FAILURE reply describing deserialization error")
    void publishOrchestrationFailureForUnparsedMessage_publishesFailure() {
        Throwable cause = new RuntimeException("Unrecognized field: unknownStep");

        sagaCommandHandler.publishOrchestrationFailureForUnparsedMessage(
                "saga-001", SagaStep.GENERATE_INVOICE_PDF, "corr-456", cause);

        verify(sagaReplyPort).publishFailure(
                eq("saga-001"), eq(SagaStep.GENERATE_INVOICE_PDF),
                eq("corr-456"),
                contains("deserialization failure"));
    }

    @Test
    @DisplayName("publishOrchestrationFailureForUnparsedMessage: swallows port exception so Camel DLQ routing continues")
    void publishOrchestrationFailureForUnparsedMessage_sagaReplyThrows_doesNotPropagate() {
        doThrow(new RuntimeException("outbox write failed"))
                .when(sagaReplyPort).publishFailure(anyString(), any(), anyString(), anyString());

        // Must not propagate — exception is logged; orchestrator falls back to saga timeout
        sagaCommandHandler.publishOrchestrationFailureForUnparsedMessage(
                "saga-001", SagaStep.GENERATE_INVOICE_PDF, "corr-456",
                new RuntimeException("cause"));
        // No assertion needed: propagation would cause a test failure above
    }

    @Test
    @DisplayName("deleteById throws during compensation → publishCompensationFailure")
    void handleCompensation_dbDeleteFails() {
        InvoicePdfDocument doc = completedDoc();
        when(pdfDocumentService.findByInvoiceId("inv-001")).thenReturn(Optional.of(doc));
        doThrow(new RuntimeException("DB error")).when(pdfDocumentService).deleteById(any());

        sagaCommandHandler.handleCompensation(compensateCommand());

        verify(pdfDocumentService).publishCompensationFailure(any(), contains("Compensation failed"));
        verify(pdfDocumentService, never()).publishCompensated(any());
    }

    // -------------------------------------------------------------------------
    // Circuit-breaker-open path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("MinIO circuit breaker OPEN on upload → failGenerationAndPublish, delete NOT called")
    void handleProcessCommand_circuitBreakerOpen_failsWithoutDelete() throws Exception {
        InvoicePdfDocument doc = generatingDoc();
        when(pdfDocumentService.findByInvoiceId("inv-001")).thenReturn(Optional.empty());
        when(pdfDocumentService.beginGeneration(anyString(), anyString())).thenReturn(doc);
        when(signedXmlFetchPort.fetch(SIGNED_XML_URL)).thenReturn(SIGNED_XML_CONTENT);
        when(pdfGenerationService.generatePdf(anyString(), anyString(), anyString()))
                .thenReturn(new byte[]{1, 2, 3});
        when(pdfStoragePort.store(anyString(), any()))
                .thenThrow(CallNotPermittedException.createCallNotPermittedException(
                        CircuitBreaker.ofDefaults("minio")));

        sagaCommandHandler.handleProcessCommand(processCommand());

        verify(pdfDocumentService).failGenerationAndPublish(
                eq(doc.getId()), contains("circuit breaker"), anyInt(), any());
        verify(pdfStoragePort, never()).delete(anyString());
    }

    // -------------------------------------------------------------------------
    // publishOrchestrationFailure (fully-parsed process command → DLQ)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("publishOrchestrationFailure: publishes FAILURE reply citing retry exhaustion")
    void publishOrchestrationFailure_publishesFailure() {
        sagaCommandHandler.publishOrchestrationFailure(
                processCommand(), new RuntimeException("processing blew up"));

        verify(sagaReplyPort).publishFailure(
                eq("saga-001"), eq(SagaStep.GENERATE_INVOICE_PDF),
                eq("corr-456"),
                contains("retry exhaustion"));
    }

    @Test
    @DisplayName("publishOrchestrationFailure: swallows port exception so Camel DLQ routing continues")
    void publishOrchestrationFailure_sagaReplyThrows_doesNotPropagate() {
        doThrow(new RuntimeException("outbox write failed"))
                .when(sagaReplyPort).publishFailure(anyString(), any(), anyString(), anyString());

        // Must not propagate — exception is logged; orchestrator falls back to saga timeout
        sagaCommandHandler.publishOrchestrationFailure(
                processCommand(), new RuntimeException("cause"));
        // No assertion needed: propagation would cause a test failure above
    }

    // -------------------------------------------------------------------------
    // OptimisticLockingFailureException handling
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("handleProcessCommand: OptimisticLockingFailureException → publishGenerationFailure with concurrent-modification message")
    void handleProcessCommand_optimisticLockingFailure_publishesFailure() {
        when(pdfDocumentService.findByInvoiceId("inv-001"))
                .thenThrow(new OptimisticLockingFailureException("version conflict"));

        sagaCommandHandler.handleProcessCommand(processCommand());

        verify(pdfDocumentService).publishGenerationFailure(
                any(), contains("Concurrent modification"));
    }
}

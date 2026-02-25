package com.wpanther.invoice.pdf.application.service;

import com.wpanther.invoice.pdf.application.port.out.PdfEventPort;
import com.wpanther.invoice.pdf.application.port.out.SagaReplyPort;
import com.wpanther.invoice.pdf.domain.event.CompensateInvoicePdfCommand;
import com.wpanther.invoice.pdf.domain.event.ProcessInvoicePdfCommand;
import com.wpanther.invoice.pdf.domain.model.GenerationStatus;
import com.wpanther.invoice.pdf.domain.model.InvoicePdfDocument;
import com.wpanther.invoice.pdf.domain.repository.InvoicePdfDocumentRepository;
import com.wpanther.saga.domain.enums.SagaStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SagaCommandHandler Unit Tests")
class SagaCommandHandlerTest {

    @Mock
    private InvoicePdfDocumentRepository repository;

    @Mock
    private InvoicePdfDocumentService pdfDocumentService;

    @Mock
    private SagaReplyPort sagaReplyPublisher;

    @Mock
    private PdfEventPort eventPublisher;

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private SagaCommandHandler sagaCommandHandler;

    private static final String SIGNED_XML_URL = "http://minio:9000/signed/invoice-signed.xml";
    private static final String SIGNED_XML_CONTENT = "<Invoice>signed</Invoice>";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(sagaCommandHandler, "maxRetries", 3);
    }

    private ProcessInvoicePdfCommand createProcessCommand() {
        return new ProcessInvoicePdfCommand(
                "saga-001", SagaStep.GENERATE_INVOICE_PDF, "corr-456",
                "doc-123", "inv-001", "INV-2024-001",
                SIGNED_XML_URL, "{}"
        );
    }

    private CompensateInvoicePdfCommand createCompensateCommand() {
        return new CompensateInvoicePdfCommand(
                "saga-001", SagaStep.GENERATE_INVOICE_PDF, "corr-456",
                "doc-123", "inv-001"
        );
    }

    private InvoicePdfDocument completedDocument() {
        return InvoicePdfDocument.builder()
                .id(UUID.randomUUID())
                .invoiceId("inv-001")
                .invoiceNumber("INV-2024-001")
                .status(GenerationStatus.COMPLETED)
                .documentPath("2024/01/15/invoice-INV-2024-001-abc.pdf")
                .documentUrl("http://localhost:9000/invoices/2024/01/15/invoice-INV-2024-001-abc.pdf")
                .fileSize(12345L)
                .xmlEmbedded(true)
                .mimeType("application/pdf")
                .retryCount(0)
                .build();
    }

    // -------------------------------------------------------------------------
    // handleProcessCommand — happy path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should generate PDF and send SUCCESS reply")
    void testHandleProcessCommand_Success() {
        ProcessInvoicePdfCommand command = createProcessCommand();
        when(repository.findByInvoiceId("inv-001")).thenReturn(Optional.empty());
        when(restTemplate.getForObject(SIGNED_XML_URL, String.class)).thenReturn(SIGNED_XML_CONTENT);

        InvoicePdfDocument document = InvoicePdfDocument.builder()
                .invoiceId("inv-001")
                .invoiceNumber("INV-2024-001")
                .status(GenerationStatus.COMPLETED)
                .documentUrl("http://localhost:9000/invoices/2024/01/15/invoice-INV-2024-001-abc.pdf")
                .fileSize(12345L)
                .build();
        when(pdfDocumentService.generatePdf(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(document);

        sagaCommandHandler.handleProcessCommand(command);

        verify(pdfDocumentService).generatePdf("inv-001", "INV-2024-001", SIGNED_XML_CONTENT, "{}");
        verify(eventPublisher).publishPdfGenerated(any());
        verify(sagaReplyPublisher).publishSuccess("saga-001", SagaStep.GENERATE_INVOICE_PDF, "corr-456",
                "http://localhost:9000/invoices/2024/01/15/invoice-INV-2024-001-abc.pdf", 12345L);
    }

    @Test
    @DisplayName("Should send SUCCESS reply for already completed document (idempotency)")
    void testHandleProcessCommand_AlreadyCompleted() {
        ProcessInvoicePdfCommand command = createProcessCommand();
        when(repository.findByInvoiceId("inv-001")).thenReturn(Optional.of(completedDocument()));

        sagaCommandHandler.handleProcessCommand(command);

        verify(pdfDocumentService, never()).generatePdf(anyString(), anyString(), anyString(), anyString());
        verify(eventPublisher).publishPdfGenerated(any());
        verify(sagaReplyPublisher).publishSuccess(eq("saga-001"), eq(SagaStep.GENERATE_INVOICE_PDF), eq("corr-456"),
                eq("http://localhost:9000/invoices/2024/01/15/invoice-INV-2024-001-abc.pdf"), eq(12345L));
    }

    // -------------------------------------------------------------------------
    // handleProcessCommand — retry logic
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should send FAILURE reply when max retries exceeded")
    void testHandleProcessCommand_MaxRetriesExceeded() {
        ProcessInvoicePdfCommand command = createProcessCommand();
        InvoicePdfDocument failedDocument = InvoicePdfDocument.builder()
                .id(UUID.randomUUID())
                .invoiceId("inv-001")
                .invoiceNumber("INV-2024-001")
                .status(GenerationStatus.FAILED)
                .retryCount(3)
                .build();
        when(repository.findByInvoiceId("inv-001")).thenReturn(Optional.of(failedDocument));

        sagaCommandHandler.handleProcessCommand(command);

        verify(pdfDocumentService, never()).generatePdf(anyString(), anyString(), anyString(), anyString());
        verify(sagaReplyPublisher).publishFailure("saga-001", SagaStep.GENERATE_INVOICE_PDF, "corr-456",
                "Maximum retry attempts exceeded");
    }

    @Test
    @DisplayName("Should delete failed record and retry when below max retries")
    void testHandleProcessCommand_RetryBelowMax() {
        ProcessInvoicePdfCommand command = createProcessCommand();
        UUID failedId = UUID.randomUUID();
        InvoicePdfDocument failedDocument = InvoicePdfDocument.builder()
                .id(failedId)
                .invoiceId("inv-001")
                .invoiceNumber("INV-2024-001")
                .status(GenerationStatus.FAILED)
                .retryCount(1)
                .build();
        when(repository.findByInvoiceId("inv-001")).thenReturn(Optional.of(failedDocument));
        when(restTemplate.getForObject(SIGNED_XML_URL, String.class)).thenReturn(SIGNED_XML_CONTENT);
        InvoicePdfDocument newDoc = InvoicePdfDocument.builder()
                .invoiceId("inv-001")
                .invoiceNumber("INV-2024-001")
                .status(GenerationStatus.COMPLETED)
                .documentUrl("http://minio/invoices/test.pdf")
                .fileSize(5000L)
                .retryCount(0)
                .build();
        when(pdfDocumentService.generatePdf(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(newDoc);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        sagaCommandHandler.handleProcessCommand(command);

        verify(repository).deleteById(failedId);
        verify(repository).flush();
        verify(sagaReplyPublisher).publishSuccess(anyString(), any(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("Should carry forward retry count from previous failed attempt")
    void testHandleProcessCommand_RetryCountCarriedForward() {
        ProcessInvoicePdfCommand command = createProcessCommand();
        InvoicePdfDocument previousFailed = InvoicePdfDocument.builder()
                .id(UUID.randomUUID())
                .invoiceId("inv-001")
                .invoiceNumber("INV-2024-001")
                .status(GenerationStatus.FAILED)
                .retryCount(1)
                .build();
        when(repository.findByInvoiceId("inv-001")).thenReturn(Optional.of(previousFailed));
        when(restTemplate.getForObject(SIGNED_XML_URL, String.class)).thenReturn(SIGNED_XML_CONTENT);
        InvoicePdfDocument newDoc = InvoicePdfDocument.builder()
                .invoiceId("inv-001")
                .invoiceNumber("INV-2024-001")
                .status(GenerationStatus.COMPLETED)
                .documentUrl("http://minio/invoices/test.pdf")
                .fileSize(5000L)
                .retryCount(0)
                .build();
        when(pdfDocumentService.generatePdf(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(newDoc);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        sagaCommandHandler.handleProcessCommand(command);

        // retryCount should be carried forward: previous(1) + 1 = 2
        ArgumentCaptor<InvoicePdfDocument> captor = ArgumentCaptor.forClass(InvoicePdfDocument.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getRetryCount()).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // handleProcessCommand — failure paths
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should send FAILURE reply and save retry count when generatePdf() returns FAILED document")
    void testHandleProcessCommand_GenerationFails() {
        ProcessInvoicePdfCommand command = createProcessCommand();
        when(repository.findByInvoiceId("inv-001")).thenReturn(Optional.empty());
        when(restTemplate.getForObject(SIGNED_XML_URL, String.class)).thenReturn(SIGNED_XML_CONTENT);
        InvoicePdfDocument failedDoc = InvoicePdfDocument.builder()
                .id(UUID.randomUUID())
                .invoiceId("inv-001")
                .invoiceNumber("INV-2024-001")
                .status(GenerationStatus.FAILED)
                .errorMessage("FOP transform failed")
                .retryCount(0)
                .build();
        when(pdfDocumentService.generatePdf(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(failedDoc);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        sagaCommandHandler.handleProcessCommand(command);

        verify(eventPublisher, never()).publishPdfGenerated(any());
        verify(sagaReplyPublisher).publishFailure(eq("saga-001"), eq(SagaStep.GENERATE_INVOICE_PDF),
                eq("corr-456"), eq("FOP transform failed"));
        // retry count must be persisted so isMaxRetriesExceeded() works on next attempt
        verify(repository).save(any());
    }

    @Test
    @DisplayName("Should persist incremented retry count when retry attempt also fails")
    void testHandleProcessCommand_RetryAttemptAlsoFails() {
        ProcessInvoicePdfCommand command = createProcessCommand();
        InvoicePdfDocument previousFailed = InvoicePdfDocument.builder()
                .id(UUID.randomUUID())
                .invoiceId("inv-001")
                .invoiceNumber("INV-2024-001")
                .status(GenerationStatus.FAILED)
                .retryCount(1)
                .build();
        when(repository.findByInvoiceId("inv-001")).thenReturn(Optional.of(previousFailed));
        when(restTemplate.getForObject(SIGNED_XML_URL, String.class)).thenReturn(SIGNED_XML_CONTENT);
        InvoicePdfDocument failedDoc = InvoicePdfDocument.builder()
                .id(UUID.randomUUID())
                .invoiceId("inv-001")
                .invoiceNumber("INV-2024-001")
                .status(GenerationStatus.FAILED)
                .errorMessage("FOP transform failed again")
                .retryCount(0)
                .build();
        when(pdfDocumentService.generatePdf(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(failedDoc);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        sagaCommandHandler.handleProcessCommand(command);

        // retryCount must be carried forward: previous(1) + 1 = 2, then saved
        ArgumentCaptor<InvoicePdfDocument> captor = ArgumentCaptor.forClass(InvoicePdfDocument.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getRetryCount()).isEqualTo(2);
        verify(sagaReplyPublisher).publishFailure(anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("Should send FAILURE reply when signed XML download returns null")
    void testHandleProcessCommand_NullSignedXml() {
        ProcessInvoicePdfCommand command = createProcessCommand();
        when(repository.findByInvoiceId("inv-001")).thenReturn(Optional.empty());
        when(restTemplate.getForObject(SIGNED_XML_URL, String.class)).thenReturn(null);

        sagaCommandHandler.handleProcessCommand(command);

        verify(pdfDocumentService, never()).generatePdf(anyString(), anyString(), anyString(), anyString());
        verify(sagaReplyPublisher).publishFailure(eq("saga-001"), eq(SagaStep.GENERATE_INVOICE_PDF),
                eq("corr-456"), contains("Failed to download signed XML"));
    }

    @Test
    @DisplayName("Should send FAILURE reply when RestTemplate throws")
    void testHandleProcessCommand_RestTemplateFails() {
        ProcessInvoicePdfCommand command = createProcessCommand();
        when(repository.findByInvoiceId("inv-001")).thenReturn(Optional.empty());
        when(restTemplate.getForObject(SIGNED_XML_URL, String.class))
                .thenThrow(new RuntimeException("Connection refused"));

        sagaCommandHandler.handleProcessCommand(command);

        verify(sagaReplyPublisher).publishFailure(eq("saga-001"), eq(SagaStep.GENERATE_INVOICE_PDF),
                eq("corr-456"), contains("RuntimeException"));
    }

    // -------------------------------------------------------------------------
    // handleCompensation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should delete document and send COMPENSATED reply")
    void testHandleCompensation_Success() {
        CompensateInvoicePdfCommand command = createCompensateCommand();
        InvoicePdfDocument document = completedDocument();
        when(repository.findByInvoiceId("inv-001")).thenReturn(Optional.of(document));

        sagaCommandHandler.handleCompensation(command);

        verify(pdfDocumentService).deletePdfFile(document.getDocumentPath());
        verify(repository).deleteById(document.getId());
        verify(sagaReplyPublisher).publishCompensated("saga-001", SagaStep.GENERATE_INVOICE_PDF, "corr-456");
    }

    @Test
    @DisplayName("Should send COMPENSATED reply even when no document exists")
    void testHandleCompensation_NoDocumentFound() {
        CompensateInvoicePdfCommand command = createCompensateCommand();
        when(repository.findByInvoiceId("inv-001")).thenReturn(Optional.empty());

        sagaCommandHandler.handleCompensation(command);

        verify(pdfDocumentService, never()).deletePdfFile(anyString());
        verify(repository, never()).deleteById(any());
        verify(sagaReplyPublisher).publishCompensated("saga-001", SagaStep.GENERATE_INVOICE_PDF, "corr-456");
    }

    @Test
    @DisplayName("Should send FAILURE reply when compensation throws")
    void testHandleCompensation_Failure() {
        CompensateInvoicePdfCommand command = createCompensateCommand();
        InvoicePdfDocument document = completedDocument();
        when(repository.findByInvoiceId("inv-001")).thenReturn(Optional.of(document));
        doThrow(new RuntimeException("Delete failed"))
                .when(pdfDocumentService).deletePdfFile(anyString());

        sagaCommandHandler.handleCompensation(command);

        verify(sagaReplyPublisher).publishFailure(eq("saga-001"), eq(SagaStep.GENERATE_INVOICE_PDF),
                eq("corr-456"), contains("Compensation failed"));
    }
}

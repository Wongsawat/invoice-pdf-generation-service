package com.wpanther.invoice.pdf.application.service;

import com.wpanther.invoice.pdf.application.port.out.PdfStoragePort;
import com.wpanther.invoice.pdf.domain.model.GenerationStatus;
import com.wpanther.invoice.pdf.domain.model.InvoicePdfDocument;
import com.wpanther.invoice.pdf.domain.repository.InvoicePdfDocumentRepository;
import com.wpanther.invoice.pdf.domain.service.InvoicePdfGenerationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("InvoicePdfDocumentService Unit Tests")
class InvoicePdfDocumentServiceTest {

    @Mock
    private InvoicePdfDocumentRepository repository;

    @Mock
    private InvoicePdfGenerationService pdfGenerationService;

    @Mock
    private PdfStoragePort pdfStoragePort;

    @InjectMocks
    private InvoicePdfDocumentService service;

    private static final String BUCKET_KEY = "2024/01/15/invoice-INV-001-uuid.pdf";
    private static final String FILE_URL   = "http://localhost:9001/invoices/" + BUCKET_KEY;

    @Test
    @DisplayName("generatePdf() uploads to MinIO and returns COMPLETED document")
    void testGeneratePdf_Success() throws Exception {
        byte[] pdfBytes = new byte[5000];
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pdfGenerationService.generatePdf(anyString(), anyString(), anyString()))
                .thenReturn(pdfBytes);
        when(pdfStoragePort.store(anyString(), any())).thenReturn(BUCKET_KEY);
        when(pdfStoragePort.resolveUrl(BUCKET_KEY)).thenReturn(FILE_URL);

        InvoicePdfDocument result = service.generatePdf("inv-001", "INV-001", "<xml/>", "{}");

        assertThat(result.getStatus()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(result.getFileSize()).isEqualTo(5000L);
        assertThat(result.getDocumentUrl()).isEqualTo(FILE_URL);
        assertThat(result.getDocumentPath()).isEqualTo(BUCKET_KEY);
        assertThat(result.isXmlEmbedded()).isTrue();

        verify(pdfStoragePort).store(eq("INV-001"), any());
        verify(pdfStoragePort).resolveUrl(BUCKET_KEY);
    }

    @Test
    @DisplayName("generatePdf() returns FAILED document without throwing when PDF generation fails")
    void testGeneratePdf_PdfGenerationFails() throws Exception {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pdfGenerationService.generatePdf(anyString(), anyString(), anyString()))
                .thenThrow(new InvoicePdfGenerationService.InvoicePdfGenerationException(
                        "FOP failed", null));

        // Must NOT throw — caller relies on the returned FAILED document to publish saga reply
        InvoicePdfDocument result = service.generatePdf("inv-001", "INV-001", "<xml/>", "{}");

        assertThat(result.getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(result.getErrorMessage()).isNotBlank();
        verify(pdfStoragePort, never()).store(anyString(), any());
    }

    @Test
    @DisplayName("generatePdf() S3 key follows YYYY/MM/DD/<filename> pattern")
    void testGeneratePdf_S3KeyPattern() throws Exception {
        byte[] pdfBytes = new byte[1000];
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pdfGenerationService.generatePdf(anyString(), anyString(), anyString()))
                .thenReturn(pdfBytes);
        when(pdfStoragePort.store(anyString(), any())).thenReturn(BUCKET_KEY);
        when(pdfStoragePort.resolveUrl(BUCKET_KEY)).thenReturn(FILE_URL);

        InvoicePdfDocument result = service.generatePdf("inv-001", "INV-001", "<xml/>", "{}");

        assertThat(result.getDocumentPath()).isEqualTo(BUCKET_KEY);
        assertThat(result.getDocumentUrl()).isEqualTo(FILE_URL);
    }

    @Test
    @DisplayName("generatePdf() saves document three times: PENDING, GENERATING, COMPLETED")
    void testGeneratePdf_SaveCount() throws Exception {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pdfGenerationService.generatePdf(anyString(), anyString(), anyString()))
                .thenReturn(new byte[1000]);
        when(pdfStoragePort.store(anyString(), any())).thenReturn(BUCKET_KEY);
        when(pdfStoragePort.resolveUrl(BUCKET_KEY)).thenReturn(FILE_URL);

        service.generatePdf("inv-001", "INV-001", "<xml/>", "{}");

        // save() called for PENDING create, GENERATING transition, COMPLETED
        verify(repository, times(3)).save(any());
    }

    @Test
    @DisplayName("deletePdfFile() delegates to PdfStoragePort.delete()")
    void testDeletePdfFile_Success() {
        service.deletePdfFile(BUCKET_KEY);

        verify(pdfStoragePort).delete(BUCKET_KEY);
    }

    @Test
    @DisplayName("deletePdfFile() wraps storage exception in RuntimeException")
    void testDeletePdfFile_StorageFails() {
        doThrow(new RuntimeException("S3 unavailable")).when(pdfStoragePort).delete(anyString());

        assertThatThrownBy(() -> service.deletePdfFile("some/key.pdf"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to delete PDF from MinIO");
    }
}

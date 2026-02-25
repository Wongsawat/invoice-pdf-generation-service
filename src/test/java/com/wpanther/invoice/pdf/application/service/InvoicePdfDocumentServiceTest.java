package com.wpanther.invoice.pdf.application.service;

import com.wpanther.invoice.pdf.domain.model.GenerationStatus;
import com.wpanther.invoice.pdf.domain.model.InvoicePdfDocument;
import com.wpanther.invoice.pdf.domain.repository.InvoicePdfDocumentRepository;
import com.wpanther.invoice.pdf.domain.service.InvoicePdfGenerationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

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
    private S3Client s3Client;

    @InjectMocks
    private InvoicePdfDocumentService service;

    private static final String BUCKET = "invoices";
    private static final String BASE_URL = "http://localhost:9000/invoices";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "bucketName", BUCKET);
        ReflectionTestUtils.setField(service, "baseUrl", BASE_URL);
    }

    @Test
    @DisplayName("generatePdf() uploads to MinIO and returns COMPLETED document")
    void testGeneratePdf_Success() throws Exception {
        byte[] pdfBytes = new byte[5000];
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pdfGenerationService.generatePdf(anyString(), anyString(), anyString()))
                .thenReturn(pdfBytes);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        InvoicePdfDocument result = service.generatePdf("inv-001", "INV-001", "<xml/>", "{}");

        assertThat(result.getStatus()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(result.getFileSize()).isEqualTo(5000L);
        assertThat(result.getDocumentUrl()).startsWith(BASE_URL + "/");
        assertThat(result.getDocumentPath()).isNotBlank();
        assertThat(result.isXmlEmbedded()).isTrue();

        ArgumentCaptor<PutObjectRequest> putCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(putCaptor.capture(), any(RequestBody.class));
        assertThat(putCaptor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(putCaptor.getValue().contentType()).isEqualTo("application/pdf");
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
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("generatePdf() S3 key follows YYYY/MM/DD/<filename> pattern")
    void testGeneratePdf_S3KeyPattern() throws Exception {
        byte[] pdfBytes = new byte[1000];
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pdfGenerationService.generatePdf(anyString(), anyString(), anyString()))
                .thenReturn(pdfBytes);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        InvoicePdfDocument result = service.generatePdf("inv-001", "INV-001", "<xml/>", "{}");

        assertThat(result.getDocumentPath()).matches("\\d{4}/\\d{2}/\\d{2}/invoice-.+\\.pdf");
        assertThat(result.getDocumentUrl())
                .startsWith(BASE_URL + "/")
                .endsWith(result.getDocumentPath());
    }

    @Test
    @DisplayName("generatePdf() saves document three times: PENDING, GENERATING, COMPLETED")
    void testGeneratePdf_SaveCount() throws Exception {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pdfGenerationService.generatePdf(anyString(), anyString(), anyString()))
                .thenReturn(new byte[1000]);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        service.generatePdf("inv-001", "INV-001", "<xml/>", "{}");

        // save() called for PENDING create, GENERATING transition, COMPLETED
        verify(repository, times(3)).save(any());
    }

    @Test
    @DisplayName("deletePdfFile() sends DeleteObjectRequest to S3")
    void testDeletePdfFile_Success() {
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());

        service.deletePdfFile("2024/01/15/invoice-INV-001-abc.pdf");

        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(captor.getValue().key()).isEqualTo("2024/01/15/invoice-INV-001-abc.pdf");
    }

    @Test
    @DisplayName("deletePdfFile() wraps S3 exception in RuntimeException")
    void testDeletePdfFile_S3Fails() {
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(new RuntimeException("S3 unavailable"));

        assertThatThrownBy(() -> service.deletePdfFile("some/key.pdf"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to delete PDF from MinIO");
    }
}

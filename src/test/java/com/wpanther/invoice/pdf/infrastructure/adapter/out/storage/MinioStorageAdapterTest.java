package com.wpanther.invoice.pdf.infrastructure.adapter.out.storage;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("MinioStorageAdapter Unit Tests")
class MinioStorageAdapterTest {

    @Mock
    private S3Client s3Client;

    private MinioStorageAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new MinioStorageAdapter(
                s3Client, "test-invoices", "http://localhost:9001/test-invoices",
                new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("store() returns a date-based key matching YYYY/MM/DD/invoice-*.pdf")
    void store_validBytes_returnsDateBasedKey() {
        byte[] pdfBytes = new byte[]{1, 2, 3};
        LocalDate today = LocalDate.now();
        // buildKey() allows hyphens, so "INV-001" is kept as-is (not sanitized to "INV_001")
        String expectedPrefix = String.format("%04d/%02d/%02d/invoice-INV-001-",
                today.getYear(), today.getMonthValue(), today.getDayOfMonth());

        String key = adapter.store("INV-001", pdfBytes);

        assertThat(key).startsWith(expectedPrefix);
        assertThat(key).endsWith(".pdf");
    }

    @Test
    @DisplayName("store() sends PutObjectRequest to the configured bucket with correct content-type")
    void store_validBytes_putsObjectWithCorrectBucketAndContentType() {
        byte[] pdfBytes = new byte[]{10, 20, 30};
        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);

        adapter.store("INV-001", pdfBytes);

        verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));
        PutObjectRequest request = requestCaptor.getValue();
        assertThat(request.bucket()).isEqualTo("test-invoices");
        assertThat(request.contentType()).isEqualTo("application/pdf");
        assertThat(request.contentLength()).isEqualTo(pdfBytes.length);
    }

    @Test
    @DisplayName("store() with special characters in invoice number sanitizes the key")
    void store_specialCharsInInvoiceNumber_sanitizesKey() {
        byte[] pdfBytes = new byte[]{1};
        String invoiceNumber = "INV/2024?01=001";

        String key = adapter.store(invoiceNumber, pdfBytes);

        // slashes, question marks, equals signs must be replaced with underscores
        assertThat(key).doesNotContain("?").doesNotContain("=");
        // The sanitized name portion should contain only safe chars
        String fileName = key.substring(key.lastIndexOf('/') + 1);
        assertThat(fileName).matches("invoice-[a-zA-Z0-9_\\-]+-[a-f0-9\\-]+\\.pdf");
    }

    @Test
    @DisplayName("store() with null bytes throws IllegalArgumentException")
    void store_nullBytes_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> adapter.store("INV-001", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pdfBytes cannot be null or empty");
    }

    @Test
    @DisplayName("store() with empty bytes throws IllegalArgumentException")
    void store_emptyBytes_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> adapter.store("INV-001", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pdfBytes cannot be null or empty");
    }

    @Test
    @DisplayName("delete() sends DeleteObjectRequest with correct bucket and key")
    void delete_callsS3WithCorrectBucketAndKey() {
        String key = "2024/01/15/invoice-INV_001-uuid.pdf";
        ArgumentCaptor<DeleteObjectRequest> requestCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);

        adapter.delete(key);

        verify(s3Client).deleteObject(requestCaptor.capture());
        DeleteObjectRequest request = requestCaptor.getValue();
        assertThat(request.bucket()).isEqualTo("test-invoices");
        assertThat(request.key()).isEqualTo(key);
    }

    @Test
    @DisplayName("resolveUrl() concatenates baseUrl and key with a separator")
    void resolveUrl_returnsBaseUrlPlusKey() {
        String key = "2024/01/15/invoice-INV_001-uuid.pdf";

        String url = adapter.resolveUrl(key);

        assertThat(url).isEqualTo("http://localhost:9001/test-invoices/" + key);
    }

    @Test
    @DisplayName("resolveUrl() strips leading slash from key to avoid double slash")
    void resolveUrl_keyWithLeadingSlash_noDoubleSlash() {
        String key = "/2024/01/15/invoice-INV_001-uuid.pdf";

        String url = adapter.resolveUrl(key);

        assertThat(url).isEqualTo("http://localhost:9001/test-invoices/2024/01/15/invoice-INV_001-uuid.pdf");
        assertThat(url).doesNotContain("//2024");
    }

    @Test
    @DisplayName("Constructor rejects a non-absolute baseUrl with IllegalStateException")
    void constructor_invalidBaseUrl_throwsIllegalStateException() {
        assertThatThrownBy(() -> new MinioStorageAdapter(
                s3Client, "test-invoices", "not-a-url", new SimpleMeterRegistry()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.minio.base-url");
    }

    @Test
    @DisplayName("resolveUrl() strips trailing slash from baseUrl to avoid double slash")
    void resolveUrl_baseUrlWithTrailingSlash_noDoubleSlash() {
        MinioStorageAdapter adapterWithSlash = new MinioStorageAdapter(
                s3Client, "test-invoices", "http://localhost:9001/test-invoices/",
                new SimpleMeterRegistry());
        String key = "2024/01/15/invoice-INV_001-uuid.pdf";

        String url = adapterWithSlash.resolveUrl(key);

        assertThat(url).isEqualTo("http://localhost:9001/test-invoices/" + key);
        assertThat(url).doesNotContain("//2024");
    }
}

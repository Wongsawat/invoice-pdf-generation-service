package com.wpanther.invoice.pdf.infrastructure.adapter.out.storage;

import com.wpanther.invoice.pdf.application.port.out.PdfStoragePort;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * MinIO/S3-backed implementation of {@link PdfStoragePort}.
 * All AWS SDK types are confined to this adapter.
 */
@Component
@Slf4j
public class MinioStorageAdapter implements PdfStoragePort {

    private final S3Client s3Client;
    private final String bucketName;
    private final String baseUrl;   // trimmed: no trailing slash
    private final Timer storeTimer;
    private final Timer deleteTimer;

    public MinioStorageAdapter(S3Client s3Client,
                               @Value("${app.minio.bucket-name}") String bucketName,
                               @Value("${app.minio.base-url}") String baseUrl,
                               MeterRegistry meterRegistry) {
        try {
            URI uri = URI.create(baseUrl);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("not an absolute URL");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.minio.base-url is not a valid absolute URL: " + baseUrl, e);
        }
        this.s3Client    = s3Client;
        this.bucketName  = bucketName;
        this.baseUrl     = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.storeTimer  = meterRegistry.timer("pdf.minio.store",  "bucket", bucketName);
        this.deleteTimer = meterRegistry.timer("pdf.minio.delete", "bucket", bucketName);
    }

    @Override
    @CircuitBreaker(name = "minio")
    public String store(String invoiceNumber, byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new IllegalArgumentException("pdfBytes cannot be null or empty");
        }
        return storeTimer.record(() -> {
            String key = buildKey(invoiceNumber);
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType("application/pdf")
                    .contentLength((long) pdfBytes.length)
                    .build();
            s3Client.putObject(request, RequestBody.fromBytes(pdfBytes));
            log.info("Uploaded PDF to MinIO: bucket={}, key={}, size={} bytes", bucketName, key, pdfBytes.length);
            return key;
        });
    }

    @Override
    @CircuitBreaker(name = "minio")
    public void delete(String key) {
        deleteTimer.record(() -> {
            DeleteObjectRequest request = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();
            s3Client.deleteObject(request);
            log.info("Deleted PDF from MinIO: bucket={}, key={}", bucketName, key);
        });
    }

    @Override
    public String resolveUrl(String key) {
        String normalizedKey = key.startsWith("/") ? key.substring(1) : key;
        return baseUrl + "/" + normalizedKey;
    }

    private String buildKey(String invoiceNumber) {
        LocalDate now = LocalDate.now();
        String safeName = invoiceNumber.replaceAll("[^a-zA-Z0-9\\-_]", "_");
        String fileName = String.format("invoice-%s-%s.pdf", safeName, UUID.randomUUID());
        return String.format("%04d/%02d/%02d/%s",
                now.getYear(), now.getMonthValue(), now.getDayOfMonth(), fileName);
    }

    /**
     * List all PDF objects in the MinIO bucket.
     * <p>
     * Used by periodic cleanup job to find orphaned objects.
     * This operation bypasses the circuit breaker as it's a maintenance task.
     *
     * @return list of all S3 object keys in the bucket
     */
    public List<String> listAllPdfs() {
        ListObjectsV2Request request = ListObjectsV2Request.builder()
                .bucket(bucketName)
                .build();
        ListObjectsV2Response response = s3Client.listObjectsV2(request);
        return response.contents().stream()
                .map(S3Object::key)
                .collect(Collectors.toList());
    }

    /**
     * Delete an object from MinIO without circuit breaker protection.
     * <p>
     * Used by periodic cleanup job to remove orphaned objects.
     * Failures are logged but do not throw exceptions to avoid
     * interrupting batch cleanup operations.
     *
     * @param key the S3 object key to delete
     */
    public void deleteWithoutCircuitBreaker(String key) {
        try {
            doDelete(key);
        } catch (Exception e) {
            log.warn("Failed to delete orphaned PDF from MinIO: bucket={}, key={}, error={}",
                    bucketName, key, e.getMessage());
        }
    }

    /**
     * Internal delete method without circuit breaker or timer instrumentation.
     * Used by deleteWithoutCircuitBreaker for cleanup operations.
     *
     * @param key the S3 object key to delete
     */
    private void doDelete(String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build());
        log.info("Deleted PDF from MinIO (without CB): bucket={}, key={}", bucketName, key);
    }
}

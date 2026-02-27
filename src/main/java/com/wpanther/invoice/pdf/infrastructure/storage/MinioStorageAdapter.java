package com.wpanther.invoice.pdf.infrastructure.storage;

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
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

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
}

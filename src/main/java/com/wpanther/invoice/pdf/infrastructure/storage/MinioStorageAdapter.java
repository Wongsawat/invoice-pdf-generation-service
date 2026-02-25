package com.wpanther.invoice.pdf.infrastructure.storage;

import com.wpanther.invoice.pdf.application.port.out.PdfStoragePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.LocalDate;
import java.util.UUID;

/**
 * MinIO/S3-backed implementation of {@link PdfStoragePort}.
 * All AWS SDK types are confined to this adapter.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MinioStorageAdapter implements PdfStoragePort {

    private final S3Client s3Client;

    @Value("${app.minio.bucket-name}")
    private String bucketName;

    @Value("${app.minio.base-url}")
    private String baseUrl;

    @Override
    public String store(String invoiceNumber, byte[] pdfBytes) {
        String key = buildKey(invoiceNumber);

        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType("application/pdf")
                .contentLength((long) pdfBytes.length)
                .build();

        s3Client.putObject(request, RequestBody.fromBytes(pdfBytes));
        log.debug("Uploaded PDF to MinIO: bucket={}, key={}", bucketName, key);
        return key;
    }

    @Override
    public void delete(String key) {
        DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();
        s3Client.deleteObject(request);
        log.info("Deleted PDF from MinIO: bucket={}, key={}", bucketName, key);
    }

    @Override
    public String resolveUrl(String key) {
        return baseUrl + "/" + key;
    }

    private String buildKey(String invoiceNumber) {
        LocalDate now = LocalDate.now();
        String safeName = invoiceNumber.replaceAll("[^a-zA-Z0-9\\-_]", "_");
        String fileName = String.format("invoice-%s-%s.pdf", safeName, UUID.randomUUID());
        return String.format("%04d/%02d/%02d/%s",
                now.getYear(), now.getMonthValue(), now.getDayOfMonth(), fileName);
    }
}

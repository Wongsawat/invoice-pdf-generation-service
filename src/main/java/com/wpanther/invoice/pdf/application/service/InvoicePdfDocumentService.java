package com.wpanther.invoice.pdf.application.service;

import com.wpanther.invoice.pdf.domain.model.InvoicePdfDocument;
import com.wpanther.invoice.pdf.domain.repository.InvoicePdfDocumentRepository;
import com.wpanther.invoice.pdf.domain.service.InvoicePdfGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.LocalDate;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class InvoicePdfDocumentService {

    private final InvoicePdfDocumentRepository repository;
    private final InvoicePdfGenerationService pdfGenerationService;
    private final S3Client s3Client;

    @Value("${app.minio.bucket-name}")
    private String bucketName;

    @Value("${app.minio.base-url}")
    private String baseUrl;

    @Transactional
    public InvoicePdfDocument generatePdf(
        String invoiceId,
        String invoiceNumber,
        String xmlContent,
        String invoiceDataJson
    ) {
        log.info("Generating PDF for invoice: {}", invoiceNumber);

        InvoicePdfDocument document = InvoicePdfDocument.builder()
            .invoiceId(invoiceId)
            .invoiceNumber(invoiceNumber)
            .build();

        document = repository.save(document);

        try {
            document.startGeneration();
            document = repository.save(document);

            byte[] pdfBytes = pdfGenerationService.generatePdf(
                invoiceNumber, xmlContent, invoiceDataJson);

            String s3Key = uploadToMinIO(invoiceNumber, pdfBytes);
            String fileUrl = baseUrl + "/" + s3Key;

            document.markCompleted(s3Key, fileUrl, pdfBytes.length);
            document.markXmlEmbedded();
            document = repository.save(document);

            log.info("Successfully generated and uploaded PDF for invoice: {} (size: {} bytes, key: {})",
                invoiceNumber, pdfBytes.length, s3Key);

            return document;

        } catch (Exception e) {
            log.error("Failed to generate PDF for invoice: {}", invoiceNumber, e);
            document.markFailed(e.getMessage());
            document = repository.save(document);
            return document;
        }
    }

    private String uploadToMinIO(String invoiceNumber, byte[] pdfBytes) {
        LocalDate now = LocalDate.now();
        String fileName = String.format("invoice-%s-%s.pdf",
            invoiceNumber.replaceAll("[^a-zA-Z0-9\\-_]", "_"),
            UUID.randomUUID());
        String s3Key = String.format("%04d/%02d/%02d/%s",
            now.getYear(), now.getMonthValue(), now.getDayOfMonth(), fileName);

        PutObjectRequest putRequest = PutObjectRequest.builder()
            .bucket(bucketName)
            .key(s3Key)
            .contentType("application/pdf")
            .contentLength((long) pdfBytes.length)
            .build();

        s3Client.putObject(putRequest, RequestBody.fromBytes(pdfBytes));
        log.debug("Uploaded PDF to MinIO: bucket={}, key={}", bucketName, s3Key);

        return s3Key;
    }

    public void deletePdfFile(String s3Key) {
        try {
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .build();
            s3Client.deleteObject(deleteRequest);
            log.info("Deleted PDF from MinIO: bucket={}, key={}", bucketName, s3Key);
        } catch (Exception e) {
            log.error("Failed to delete PDF from MinIO: key={}", s3Key, e);
            throw new RuntimeException("Failed to delete PDF from MinIO", e);
        }
    }
}

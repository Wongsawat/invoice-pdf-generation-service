package com.wpanther.invoice.pdf.application.service;

import com.wpanther.invoice.pdf.domain.model.InvoicePdfDocument;
import com.wpanther.invoice.pdf.domain.service.InvoicePdfGenerationService;
import com.wpanther.invoice.pdf.infrastructure.persistence.InvoicePdfDocumentEntity;
import com.wpanther.invoice.pdf.infrastructure.persistence.JpaInvoicePdfDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Application service for Invoice PDF document operations
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InvoicePdfDocumentService {

    private final JpaInvoicePdfDocumentRepository repository;
    private final InvoicePdfGenerationService pdfGenerationService;

    @Value("${app.pdf.storage.path:/var/documents/invoices}")
    private String storagePath;

    @Value("${app.pdf.storage.base-url:http://localhost:8084}")
    private String baseUrl;

    /**
     * Generate PDF document for invoice
     */
    @Transactional
    public InvoicePdfDocument generatePdf(
        String invoiceId,
        String invoiceNumber,
        String xmlContent,
        String invoiceDataJson
    ) {
        log.info("Generating PDF for invoice: {}", invoiceNumber);

        // Create PDF document aggregate
        InvoicePdfDocument document = InvoicePdfDocument.builder()
            .invoiceId(invoiceId)
            .invoiceNumber(invoiceNumber)
            .build();

        // Save initial state
        document = saveDomain(document);

        try {
            // Start generation
            document.startGeneration();
            document = saveDomain(document);

            // Generate PDF bytes
            byte[] pdfBytes = pdfGenerationService.generatePdf(
                invoiceNumber, xmlContent, invoiceDataJson);

            // Store PDF file
            String filePath = saveToFileSystem(invoiceNumber, pdfBytes);
            String fileUrl = generateUrl(filePath);

            // Mark as completed
            document.markCompleted(filePath, fileUrl, pdfBytes.length);
            document.markXmlEmbedded();
            document = saveDomain(document);

            log.info("Successfully generated PDF for invoice: {} (size: {} bytes)",
                invoiceNumber, pdfBytes.length);

            return document;

        } catch (Exception e) {
            log.error("Failed to generate PDF for invoice: {}", invoiceNumber, e);
            document.markFailed(e.getMessage());
            saveDomain(document);
            throw new RuntimeException("Invoice PDF generation failed", e);
        }
    }

    /**
     * Save PDF bytes to filesystem
     */
    private String saveToFileSystem(String invoiceNumber, byte[] pdfBytes) throws Exception {
        // Create directory structure: storage/YYYY/MM/DD/
        LocalDate now = LocalDate.now();
        Path dir = Paths.get(storagePath, String.valueOf(now.getYear()),
            String.format("%02d", now.getMonthValue()),
            String.format("%02d", now.getDayOfMonth()));

        Files.createDirectories(dir);

        // Save file
        String fileName = String.format("invoice-%s-%s.pdf", invoiceNumber, UUID.randomUUID());
        Path filePath = dir.resolve(fileName);
        Files.write(filePath, pdfBytes);

        log.debug("Saved PDF to: {}", filePath);

        return filePath.toString();
    }

    /**
     * Generate URL for accessing the document
     */
    private String generateUrl(String filePath) {
        // Convert filesystem path to URL path
        String relativePath = filePath.replace(storagePath, "").replace("\\", "/");
        return baseUrl + "/documents" + relativePath;
    }

    /**
     * Save domain model to database
     */
    private InvoicePdfDocument saveDomain(InvoicePdfDocument document) {
        InvoicePdfDocumentEntity entity = InvoicePdfDocumentEntity.builder()
            .id(document.getId())
            .invoiceId(document.getInvoiceId())
            .invoiceNumber(document.getInvoiceNumber())
            .documentPath(document.getDocumentPath())
            .documentUrl(document.getDocumentUrl())
            .fileSize(document.getFileSize())
            .mimeType(document.getMimeType())
            .xmlEmbedded(document.isXmlEmbedded())
            .status(document.getStatus())
            .errorMessage(document.getErrorMessage())
            .createdAt(document.getCreatedAt())
            .completedAt(document.getCompletedAt())
            .build();

        entity = repository.save(entity);

        return InvoicePdfDocument.builder()
            .id(entity.getId())
            .invoiceId(entity.getInvoiceId())
            .invoiceNumber(entity.getInvoiceNumber())
            .documentPath(entity.getDocumentPath())
            .documentUrl(entity.getDocumentUrl())
            .fileSize(entity.getFileSize() != null ? entity.getFileSize() : 0)
            .mimeType(entity.getMimeType())
            .xmlEmbedded(entity.getXmlEmbedded())
            .status(entity.getStatus())
            .errorMessage(entity.getErrorMessage())
            .createdAt(entity.getCreatedAt())
            .completedAt(entity.getCompletedAt())
            .build();
    }
}

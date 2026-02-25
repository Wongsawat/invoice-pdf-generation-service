package com.wpanther.invoice.pdf.application.service;

import com.wpanther.invoice.pdf.application.port.out.PdfStoragePort;
import com.wpanther.invoice.pdf.domain.model.InvoicePdfDocument;
import com.wpanther.invoice.pdf.domain.repository.InvoicePdfDocumentRepository;
import com.wpanther.invoice.pdf.domain.service.InvoicePdfGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class InvoicePdfDocumentService {

    private final InvoicePdfDocumentRepository repository;
    private final InvoicePdfGenerationService pdfGenerationService;
    private final PdfStoragePort pdfStoragePort;

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

            String s3Key = pdfStoragePort.store(invoiceNumber, pdfBytes);
            String fileUrl = pdfStoragePort.resolveUrl(s3Key);

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

    public void deletePdfFile(String s3Key) {
        try {
            pdfStoragePort.delete(s3Key);
        } catch (Exception e) {
            log.error("Failed to delete PDF from MinIO: key={}", s3Key, e);
            throw new RuntimeException("Failed to delete PDF from MinIO", e);
        }
    }
}

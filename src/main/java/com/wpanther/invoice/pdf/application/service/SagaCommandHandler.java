package com.wpanther.invoice.pdf.application.service;

import com.wpanther.invoice.pdf.application.port.out.PdfStoragePort;
import com.wpanther.invoice.pdf.application.port.out.SagaReplyPort;
import com.wpanther.invoice.pdf.domain.event.CompensateInvoicePdfCommand;
import com.wpanther.invoice.pdf.domain.event.ProcessInvoicePdfCommand;
import com.wpanther.invoice.pdf.domain.model.InvoicePdfDocument;
import com.wpanther.invoice.pdf.domain.repository.InvoicePdfDocumentRepository;
import com.wpanther.invoice.pdf.domain.service.InvoicePdfGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

/**
 * Orchestrates invoice PDF generation in response to saga commands.
 *
 * <h3>Transaction boundary design</h3>
 * <p>This class does NOT hold an open DB transaction during long-running work.
 * Instead it delegates every short DB operation to {@link InvoicePdfDocumentService},
 * whose methods each open and commit their own focused transaction.  The sequence is:
 * <ol>
 *   <li><b>Short tx 1</b> — {@code pdfDocumentService.beginGeneration()}: create PENDING → GENERATING.</li>
 *   <li><b>No transaction</b> — download signed XML, run FOP+PDFBox, upload to MinIO.</li>
 *   <li><b>Short tx 2</b> — {@code pdfDocumentService.completeGenerationAndPublish()} or
 *       {@code failGenerationAndPublish()}: mark COMPLETED/FAILED and write outbox events atomically.</li>
 * </ol>
 * <p>Hikari connections are never held during CPU or network I/O, which eliminates pool
 * exhaustion under concurrent load.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SagaCommandHandler {

    private final InvoicePdfDocumentRepository repository;
    private final InvoicePdfDocumentService pdfDocumentService;
    private final InvoicePdfGenerationService pdfGenerationService;
    private final PdfStoragePort pdfStoragePort;
    private final SagaReplyPort sagaReplyPort;   // used only by publishOrchestrationFailure
    private final RestTemplate restTemplate;

    @Value("${app.pdf.generation.max-retries:3}")
    private int maxRetries;

    /**
     * Handle a ProcessInvoicePdfCommand from the saga orchestrator.
     * No {@code @Transactional} — DB work is performed in short, focused transactions
     * via {@link InvoicePdfDocumentService}.
     */
    public void handleProcessCommand(ProcessInvoicePdfCommand command) {
        MDC.put("sagaId", command.getSagaId());
        MDC.put("correlationId", command.getCorrelationId());
        MDC.put("invoiceNumber", command.getInvoiceNumber());
        try {
            log.info("Handling ProcessInvoicePdfCommand for saga {} invoice {}",
                    command.getSagaId(), command.getInvoiceNumber());
            try {
                Optional<InvoicePdfDocument> existing =
                        repository.findByInvoiceId(command.getInvoiceId());

                // Idempotency: already completed — re-publish and reply SUCCESS
                if (existing.isPresent() && existing.get().isCompleted()) {
                    pdfDocumentService.publishIdempotentSuccess(existing.get(), command);
                    return;
                }

                // Retry limit check
                if (existing.isPresent() && existing.get().isFailed()) {
                    InvoicePdfDocument failed = existing.get();
                    if (failed.isMaxRetriesExceeded(maxRetries)) {
                        pdfDocumentService.publishRetryExhausted(command);
                        return;
                    }
                    // Delete the failed record; flush enforces DELETE-before-INSERT ordering
                    pdfDocumentService.deleteById(failed.getId());
                }

                // Validate signed XML URL
                String signedXmlUrl = command.getSignedXmlUrl();
                if (signedXmlUrl == null || signedXmlUrl.isBlank()) {
                    pdfDocumentService.publishGenerationFailure(
                            command, "signedXmlUrl is null or blank in saga command");
                    return;
                }

                // ── Short tx 1: create PENDING → GENERATING record ──────────────────
                InvoicePdfDocument document = pdfDocumentService.beginGeneration(
                        command.getInvoiceId(), command.getInvoiceNumber());
                int previousRetryCount =
                        existing.map(InvoicePdfDocument::getRetryCount).orElse(-1);
                // ── Transaction committed ────────────────────────────────────────────

                try {
                    // ── NO TRANSACTION HELD HERE ────────────────────────────────────

                    // Download signed XML (network I/O)
                    String signedXml = restTemplate.getForObject(signedXmlUrl, String.class);
                    if (signedXml == null || signedXml.isBlank()) {
                        throw new IllegalStateException(
                                "Failed to download signed XML from " + signedXmlUrl);
                    }

                    // Generate PDF bytes (CPU: FOP + PDFBox)
                    byte[] pdfBytes = pdfGenerationService.generatePdf(
                            command.getInvoiceNumber(), signedXml, command.getInvoiceDataJson());

                    // Upload to MinIO (network I/O)
                    String s3Key = pdfStoragePort.store(command.getInvoiceNumber(), pdfBytes);
                    String fileUrl = pdfStoragePort.resolveUrl(s3Key);

                    // ── END NO-TRANSACTION BLOCK ─────────────────────────────────────

                    // ── Short tx 2 (success): mark COMPLETED + write outbox atomically
                    pdfDocumentService.completeGenerationAndPublish(
                            document.getId(), s3Key, fileUrl, pdfBytes.length,
                            previousRetryCount, command);
                    // ── Transaction committed ────────────────────────────────────────

                    log.info("Successfully processed PDF generation for saga {} invoice {}",
                            command.getSagaId(), command.getInvoiceNumber());

                } catch (Exception e) {
                    log.error("PDF generation/upload failed for saga {} invoice {}: {}",
                            command.getSagaId(), command.getInvoiceNumber(), e.getMessage(), e);
                    // ── Short tx 2 (failure): mark FAILED + write FAILURE reply atomically
                    pdfDocumentService.failGenerationAndPublish(
                            document.getId(), describeException(e), previousRetryCount, command);
                    // ── Transaction committed ────────────────────────────────────────
                }

            } catch (Exception e) {
                log.error("Unexpected error for saga {} invoice {}: {}",
                        command.getSagaId(), command.getInvoiceNumber(), e.getMessage(), e);
                pdfDocumentService.publishGenerationFailure(command, describeException(e));
            }
        } finally {
            MDC.clear();
        }
    }

    /**
     * Handle a CompensateInvoicePdfCommand from the saga orchestrator.
     * No {@code @Transactional} — DB delete and MinIO delete run independently so neither
     * holds an open transaction during network I/O.
     */
    public void handleCompensation(CompensateInvoicePdfCommand command) {
        MDC.put("sagaId", command.getSagaId());
        MDC.put("correlationId", command.getCorrelationId());
        MDC.put("invoiceId", command.getInvoiceId());
        try {
            log.info("Handling compensation for saga {} invoice {}",
                    command.getSagaId(), command.getInvoiceId());
            try {
                Optional<InvoicePdfDocument> existing =
                        repository.findByInvoiceId(command.getInvoiceId());

                if (existing.isPresent()) {
                    InvoicePdfDocument document = existing.get();
                    // Short tx: DB delete (if this rolls back, MinIO object remains intact)
                    pdfDocumentService.deleteById(document.getId());
                    // Outside tx: MinIO delete (orphaned object acceptable if this fails)
                    if (document.getDocumentPath() != null) {
                        try {
                            pdfStoragePort.delete(document.getDocumentPath());
                        } catch (Exception e) {
                            log.warn("Failed to delete PDF from MinIO for saga {} key {}: {}",
                                    command.getSagaId(), document.getDocumentPath(), e.getMessage());
                        }
                    }
                    log.info("Compensated InvoicePdfDocument {} for saga {}",
                            document.getId(), command.getSagaId());
                } else {
                    log.info("No document found for invoiceId {} — already compensated or never processed",
                            command.getInvoiceId());
                }

                // Short tx: publish COMPENSATED reply
                pdfDocumentService.publishCompensated(command);

            } catch (Exception e) {
                log.error("Failed to compensate for saga {} invoice {}: {}",
                        command.getSagaId(), command.getInvoiceId(), e.getMessage(), e);
                pdfDocumentService.publishCompensationFailure(
                        command, "Compensation failed: " + describeException(e));
            }
        } finally {
            MDC.clear();
        }
    }

    /**
     * Best-effort failure notification when Camel routes a message to the DLQ after
     * exhausting retries.  Runs in its own transaction (REQUIRES_NEW) so a previously
     * rolled-back outer transaction does not prevent the outbox write.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishOrchestrationFailure(ProcessInvoicePdfCommand command, Throwable cause) {
        try {
            String error = "Message routed to DLQ after retry exhaustion: "
                    + describeThrowable(cause);
            sagaReplyPort.publishFailure(
                    command.getSagaId(), command.getSagaStep(), command.getCorrelationId(), error);
            log.error("Published FAILURE reply after DLQ routing for saga {} invoice {}",
                    command.getSagaId(), command.getInvoiceNumber());
        } catch (Exception e) {
            log.error("Cannot notify orchestrator of DLQ failure for saga {} — orchestrator must timeout",
                    command.getSagaId(), e);
        }
    }

    private String describeException(Exception e) {
        return describeThrowable(e);
    }

    private String describeThrowable(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message != null ? ": " + message : "");
    }
}

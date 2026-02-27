package com.wpanther.invoice.pdf.application.service;

import com.wpanther.invoice.pdf.application.port.out.PdfStoragePort;
import com.wpanther.invoice.pdf.application.port.out.SagaReplyPort;
import com.wpanther.invoice.pdf.application.port.out.SignedXmlFetchPort;
import com.wpanther.invoice.pdf.domain.event.CompensateInvoicePdfCommand;
import com.wpanther.invoice.pdf.domain.event.ProcessInvoicePdfCommand;
import com.wpanther.invoice.pdf.domain.model.InvoicePdfDocument;
import com.wpanther.invoice.pdf.domain.service.InvoicePdfGenerationService;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
@Slf4j
public class SagaCommandHandler {

    // MDC key constants — prevents typos in structured log fields
    private static final String MDC_SAGA_ID         = "sagaId";
    private static final String MDC_CORRELATION_ID  = "correlationId";
    private static final String MDC_INVOICE_NUMBER  = "invoiceNumber";
    private static final String MDC_INVOICE_ID      = "invoiceId";

    private final InvoicePdfDocumentService pdfDocumentService;
    private final InvoicePdfGenerationService pdfGenerationService;
    private final PdfStoragePort pdfStoragePort;
    private final SagaReplyPort sagaReplyPort;   // used only by publishOrchestrationFailure
    private final SignedXmlFetchPort signedXmlFetchPort;
    private final int maxRetries;

    public SagaCommandHandler(InvoicePdfDocumentService pdfDocumentService,
                              InvoicePdfGenerationService pdfGenerationService,
                              PdfStoragePort pdfStoragePort,
                              SagaReplyPort sagaReplyPort,
                              SignedXmlFetchPort signedXmlFetchPort,
                              @Value("${app.pdf.generation.max-retries:3}") int maxRetries) {
        this.pdfDocumentService = pdfDocumentService;
        this.pdfGenerationService = pdfGenerationService;
        this.pdfStoragePort = pdfStoragePort;
        this.sagaReplyPort = sagaReplyPort;
        this.signedXmlFetchPort = signedXmlFetchPort;
        this.maxRetries = maxRetries;
    }

    /**
     * Handle a ProcessInvoicePdfCommand from the saga orchestrator.
     * No {@code @Transactional} — DB work is performed in short, focused transactions
     * via {@link InvoicePdfDocumentService}.
     */
    public void handleProcessCommand(ProcessInvoicePdfCommand command) {
        MDC.put(MDC_SAGA_ID,        command.getSagaId());
        MDC.put(MDC_CORRELATION_ID, command.getCorrelationId());
        MDC.put(MDC_INVOICE_NUMBER, command.getInvoiceNumber());
        try {
            log.info("Handling ProcessInvoicePdfCommand for saga {} invoice {}",
                    command.getSagaId(), command.getInvoiceNumber());
            try {
                Optional<InvoicePdfDocument> existing =
                        pdfDocumentService.findByInvoiceId(command.getInvoiceId());

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

                // Validate invoice number
                String invoiceNumber = command.getInvoiceNumber();
                if (invoiceNumber == null || invoiceNumber.isBlank()) {
                    pdfDocumentService.publishGenerationFailure(
                            command, "invoiceNumber is null or blank in saga command");
                    return;
                }

                // ── Short tx 1: create PENDING → GENERATING record ──────────────────
                InvoicePdfDocument document = pdfDocumentService.beginGeneration(
                        command.getInvoiceId(), invoiceNumber);
                int previousRetryCount =
                        existing.map(InvoicePdfDocument::getRetryCount).orElse(-1);
                // ── Transaction committed ────────────────────────────────────────────

                String s3Key = null;
                try {
                    // ── NO TRANSACTION HELD HERE ────────────────────────────────────

                    // Download signed XML (network I/O)
                    String signedXml = signedXmlFetchPort.fetch(signedXmlUrl);

                    // Generate PDF bytes (CPU: FOP + PDFBox)
                    byte[] pdfBytes = pdfGenerationService.generatePdf(
                            invoiceNumber, signedXml, command.getInvoiceDataJson());

                    // Upload to MinIO (network I/O)
                    s3Key = pdfStoragePort.store(invoiceNumber, pdfBytes);
                    String fileUrl = pdfStoragePort.resolveUrl(s3Key);

                    // ── END NO-TRANSACTION BLOCK ─────────────────────────────────────

                    // ── Short tx 2 (success): mark COMPLETED + write outbox atomically
                    pdfDocumentService.completeGenerationAndPublish(
                            document.getId(), s3Key, fileUrl, pdfBytes.length,
                            previousRetryCount, command);
                    // ── Transaction committed ────────────────────────────────────────

                    log.debug("Successfully processed PDF generation for saga {} invoice {}",
                            command.getSagaId(), invoiceNumber);

                } catch (CallNotPermittedException e) {
                    // Circuit breaker is OPEN — MinIO is unreachable; do not attempt upload or delete
                    log.warn("MinIO circuit breaker OPEN for saga {} invoice {} — "
                            + "no upload attempted, will retry when CB re-closes: {}",
                            command.getSagaId(), invoiceNumber, e.getMessage());
                    pdfDocumentService.failGenerationAndPublish(
                            document.getId(), "MinIO circuit breaker open: " + e.getMessage(),
                            previousRetryCount, command);

                } catch (Exception e) {
                    // If upload succeeded but the DB write failed, the MinIO object is orphaned —
                    // attempt best-effort cleanup before marking the document as FAILED.
                    if (s3Key != null) {
                        try {
                            pdfStoragePort.delete(s3Key);
                            log.warn("Deleted orphaned MinIO object {} after processing failure for saga {}",
                                    s3Key, command.getSagaId());
                        } catch (Exception deleteEx) {
                            log.warn("Failed to clean up orphaned MinIO object {} for saga {}: {}",
                                    s3Key, command.getSagaId(), deleteEx.getMessage());
                        }
                    }
                    log.error("PDF generation/upload failed for saga {} invoice {}: {}",
                            command.getSagaId(), invoiceNumber, e.getMessage(), e);
                    // ── Short tx 2 (failure): mark FAILED + write FAILURE reply atomically
                    pdfDocumentService.failGenerationAndPublish(
                            document.getId(), describeThrowable(e), previousRetryCount, command);
                    // ── Transaction committed ────────────────────────────────────────
                }

            } catch (OptimisticLockingFailureException e) {
                // Concurrent modification by another consumer — this is retryable via Camel
                log.warn("Concurrent modification conflict for saga {} invoice {} — retryable: {}",
                        command.getSagaId(), command.getInvoiceNumber(), e.getMessage());
                pdfDocumentService.publishGenerationFailure(
                        command, "Concurrent modification conflict: " + e.getMessage());
            } catch (Exception e) {
                log.error("Unexpected error for saga {} invoice {}: {}",
                        command.getSagaId(), command.getInvoiceNumber(), e.getMessage(), e);
                pdfDocumentService.publishGenerationFailure(command, describeThrowable(e));
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
        MDC.put(MDC_SAGA_ID,        command.getSagaId());
        MDC.put(MDC_CORRELATION_ID, command.getCorrelationId());
        MDC.put(MDC_INVOICE_ID,     command.getInvoiceId());
        try {
            log.info("Handling compensation for saga {} invoice {}",
                    command.getSagaId(), command.getInvoiceId());
            try {
                Optional<InvoicePdfDocument> existing =
                        pdfDocumentService.findByInvoiceId(command.getInvoiceId());

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
                        command, "Compensation failed: " + describeThrowable(e));
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

    private String describeThrowable(Throwable t) {
        if (t == null) return "unknown error";
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message != null ? ": " + message : "");
    }
}

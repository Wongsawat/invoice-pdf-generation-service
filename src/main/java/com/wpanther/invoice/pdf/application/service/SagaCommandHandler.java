package com.wpanther.invoice.pdf.application.service;

import com.wpanther.invoice.pdf.application.port.out.PdfStoragePort;
import com.wpanther.invoice.pdf.application.port.out.SagaReplyPort;
import com.wpanther.invoice.pdf.application.port.out.SignedXmlFetchPort;
import com.wpanther.invoice.pdf.application.usecase.CompensateInvoicePdfUseCase;
import com.wpanther.invoice.pdf.application.usecase.ProcessInvoicePdfUseCase;
import com.wpanther.invoice.pdf.domain.event.CompensateInvoicePdfCommand;
import com.wpanther.invoice.pdf.domain.event.ProcessInvoicePdfCommand;
import com.wpanther.invoice.pdf.domain.model.InvoicePdfDocument;
import com.wpanther.invoice.pdf.domain.service.InvoicePdfGenerationService;
import com.wpanther.saga.domain.enums.SagaStep;
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
public class SagaCommandHandler implements ProcessInvoicePdfUseCase, CompensateInvoicePdfUseCase {

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
    @Override
    public void handle(ProcessInvoicePdfCommand command) {
        MDC.put(MDC_SAGA_ID,        command.getSagaId());
        MDC.put(MDC_CORRELATION_ID, command.getCorrelationId());
        MDC.put(MDC_INVOICE_NUMBER, command.getInvoiceNumber());
        MDC.put(MDC_INVOICE_ID,     command.getInvoiceId());
        try {
            log.info("Handling ProcessInvoicePdfCommand for saga {} invoice {}",
                    command.getSagaId(), command.getInvoiceNumber());
            try {
                // Validate command fields first — before any DB mutation so a permanently
                // malformed command never deletes an existing document or bypasses retry limits.
                String signedXmlUrl = command.getSignedXmlUrl();
                if (signedXmlUrl == null || signedXmlUrl.isBlank()) {
                    pdfDocumentService.publishGenerationFailure(
                            command, "signedXmlUrl is null or blank in saga command");
                    return;
                }
                String invoiceNumber = command.getInvoiceNumber();
                if (invoiceNumber == null || invoiceNumber.isBlank()) {
                    pdfDocumentService.publishGenerationFailure(
                            command, "invoiceNumber is null or blank in saga command");
                    return;
                }
                String invoiceId = command.getInvoiceId();
                if (invoiceId == null || invoiceId.isBlank()) {
                    pdfDocumentService.publishGenerationFailure(
                            command, "invoiceId is null or blank in saga command");
                    return;
                }

                Optional<InvoicePdfDocument> existing =
                        pdfDocumentService.findByInvoiceId(invoiceId);

                // Idempotency: already completed — re-publish and reply SUCCESS
                if (existing.isPresent() && existing.get().isCompleted()) {
                    pdfDocumentService.publishIdempotentSuccess(existing.get(), command);
                    return;
                }

                // Compute previousRetryCount before any mutation so it is available in
                // both the retry branch (where we pass it to replaceAndBeginGeneration)
                // and in TX2 (completeGenerationAndPublish / failGenerationAndPublish).
                int previousRetryCount =
                        existing.map(InvoicePdfDocument::getRetryCount).orElse(-1);

                // Retry limit check — handles FAILED and stuck GENERATING states.
                // A document can be left in GENERATING when TX2 (completeGenerationAndPublish /
                // failGenerationAndPublish) rolls back.  Without this branch the next re-delivery
                // would fall through to beginGeneration() and hit the unique invoice_id constraint.
                if (existing.isPresent()) {
                    InvoicePdfDocument prior = existing.get();
                    if (!prior.isFailed()) {
                        // Document is stuck in a non-terminal state — TX2 likely rolled back.
                        log.warn("Found document in non-terminal state (status={}) for invoice {} saga {} — "
                                + "TX2 may have rolled back; will delete and retry",
                                prior.getStatus(), command.getInvoiceId(), command.getSagaId());
                    }
                    if (prior.isMaxRetriesExceeded(maxRetries)) {
                        pdfDocumentService.publishRetryExhausted(command);
                        return;
                    }
                }

                // ── Short tx 1: create PENDING → GENERATING record ──────────────────
                // Retry path: replace atomically (single tx: delete + flush + insert) so the
                // retry count is never lost to a crash between two separate transactions.
                InvoicePdfDocument document;
                if (existing.isPresent()) {
                    document = pdfDocumentService.replaceAndBeginGeneration(
                            existing.get().getId(), previousRetryCount, invoiceId, invoiceNumber);
                } else {
                    document = pdfDocumentService.beginGeneration(invoiceId, invoiceNumber);
                }
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
                            log.error("[ORPHAN_PDF] s3Key={} saga={} invoiceNumber={} error={} — manual recovery required: delete object from MinIO bucket",
                                    s3Key, command.getSagaId(), invoiceNumber, describeThrowable(deleteEx));
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
    @Override
    public void handle(CompensateInvoicePdfCommand command) {
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
     * Best-effort failure notification when Camel routes a <em>process</em> message to the DLQ
     * after exhausting retries.  Runs in its own transaction (REQUIRES_NEW) so a previously
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

    /**
     * Best-effort failure notification when Camel cannot deserialize a <em>process</em> message
     * (e.g., malformed JSON or unknown enum value) and routes it to the DLQ.
     * Accepts raw saga coordinates recovered by the caller from the unparsed JSON payload.
     * Runs in its own transaction (REQUIRES_NEW).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishOrchestrationFailureForUnparsedMessage(
            String sagaId, SagaStep sagaStep, String correlationId, Throwable cause) {
        try {
            String error = "Message routed to DLQ after deserialization failure: "
                    + describeThrowable(cause);
            sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId, error);
            log.error("Published FAILURE reply after DLQ routing (deserialization failure) for saga {}", sagaId);
        } catch (Exception e) {
            log.error("Cannot notify orchestrator of DLQ deserialization failure for saga {} — orchestrator must timeout",
                    sagaId, e);
        }
    }

    /**
     * Best-effort failure notification when Camel routes a <em>compensation</em> message to the
     * DLQ after exhausting retries.  Runs in its own transaction (REQUIRES_NEW).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishCompensationOrchestrationFailure(CompensateInvoicePdfCommand command, Throwable cause) {
        try {
            String error = "Compensation message routed to DLQ after retry exhaustion: "
                    + describeThrowable(cause);
            sagaReplyPort.publishFailure(
                    command.getSagaId(), command.getSagaStep(), command.getCorrelationId(), error);
            log.error("Published FAILURE reply after compensation DLQ routing for saga {} invoice {}",
                    command.getSagaId(), command.getInvoiceId());
        } catch (Exception e) {
            log.error("Cannot notify orchestrator of compensation DLQ failure for saga {} — orchestrator must timeout",
                    command.getSagaId(), e);
        }
    }

    private String describeThrowable(Throwable t) {
        if (t == null) return "unknown error";
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message != null ? ": " + message : "");
    }
}

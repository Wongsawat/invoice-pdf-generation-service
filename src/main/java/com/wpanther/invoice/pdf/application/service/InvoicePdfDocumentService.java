package com.wpanther.invoice.pdf.application.service;

import com.wpanther.invoice.pdf.application.port.out.PdfEventPort;
import com.wpanther.invoice.pdf.application.port.out.SagaReplyPort;
import com.wpanther.invoice.pdf.domain.model.InvoicePdfDocument;
import com.wpanther.invoice.pdf.domain.repository.InvoicePdfDocumentRepository;
import com.wpanther.invoice.pdf.domain.event.CompensateInvoicePdfCommand;
import com.wpanther.invoice.pdf.domain.event.ProcessInvoicePdfCommand;
import com.wpanther.invoice.pdf.domain.event.InvoicePdfGeneratedEvent;
import com.wpanther.invoice.pdf.infrastructure.metrics.PdfGenerationMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service for invoice PDF document lifecycle management.
 *
 * <p>Each public method is a <em>short, focused</em> {@code @Transactional} unit:
 * it does only the DB work it needs and then commits.  No long-running CPU work
 * (FOP/PDFBox) or network I/O (MinIO upload/download) is performed inside any
 * transaction here — those are the caller's responsibility to execute between calls.
 *
 * <p>This design keeps Hikari connections free during potentially long PDF generation
 * and MinIO upload operations.
 */
@Service
@Slf4j
public class InvoicePdfDocumentService {

    private final InvoicePdfDocumentRepository repository;
    private final PdfEventPort pdfEventPort;
    private final SagaReplyPort sagaReplyPort;

    @Autowired(required = false)
    private PdfGenerationMetrics pdfGenerationMetrics;

    public InvoicePdfDocumentService(InvoicePdfDocumentRepository repository,
                                     PdfEventPort pdfEventPort,
                                     SagaReplyPort sagaReplyPort,
                                     PdfGenerationMetrics pdfGenerationMetrics) {
        this.repository = repository;
        this.pdfEventPort = pdfEventPort;
        this.sagaReplyPort = sagaReplyPort;
        this.pdfGenerationMetrics = pdfGenerationMetrics;
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    /** Look up an existing document by invoice ID (read-only, no state change). */
    @Transactional(readOnly = true)
    public Optional<InvoicePdfDocument> findByInvoiceId(String invoiceId) {
        return repository.findByInvoiceId(invoiceId);
    }

    // -------------------------------------------------------------------------
    // Document lifecycle
    // -------------------------------------------------------------------------

    /**
     * Phase 1: create a PENDING → GENERATING record and commit.
     * Returns the persisted document; callers use its {@code id} in subsequent calls.
     */
    @Transactional
    public InvoicePdfDocument beginGeneration(String invoiceId, String invoiceNumber) {
        log.info("Initiating PDF generation for invoice: {}", invoiceNumber);
        InvoicePdfDocument document = InvoicePdfDocument.builder()
                .invoiceId(invoiceId)
                .invoiceNumber(invoiceNumber)
                .build();
        document.startGeneration();
        return repository.save(document);
    }

    /**
     * Atomic replace-and-begin: delete the prior failed/stuck document, flush to enforce
     * DELETE-before-INSERT on the unique {@code invoice_id} key, then create a new
     * PENDING → GENERATING record — all within a <em>single</em> transaction.
     *
     * <p>Performing these three operations atomically eliminates the crash window that
     * exists when {@code deleteById} and {@code beginGeneration} are called as separate
     * transactions: a JVM crash between those two commits would lose the retry count
     * from the prior document, causing the saga to retry more times than configured.
     *
     * @param existingId        the id of the document to delete
     * @param previousRetryCount the retry count from the document being replaced
     * @param invoiceId         the invoice identifier
     * @param invoiceNumber     the invoice number
     * @return the new GENERATING document with the advanced retry count
     */
    @Transactional
    public InvoicePdfDocument replaceAndBeginGeneration(
            UUID existingId, int previousRetryCount, String invoiceId, String invoiceNumber) {
        log.info("Replacing PDF document {} and initiating new generation for invoice: {}", existingId, invoiceNumber);
        repository.deleteById(existingId);
        repository.flush();
        InvoicePdfDocument document = InvoicePdfDocument.builder()
                .invoiceId(invoiceId)
                .invoiceNumber(invoiceNumber)
                .build();
        document.startGeneration();
        document.incrementRetryCountTo(previousRetryCount + 1);
        return repository.save(document);
    }

    /**
     * Phase 3 (success path): mark COMPLETED and atomically write both the
     * pdf-generated notification event and the SUCCESS saga reply to the outbox.
     * DB connection is held only for these writes — PDF bytes are already uploaded.
     */
    @Transactional
    public void completeGenerationAndPublish(UUID documentId, String s3Key, String fileUrl,
                                             long fileSize, int previousRetryCount,
                                             ProcessInvoicePdfCommand command) {
        InvoicePdfDocument doc = requireDocument(documentId);
        doc.markCompleted(s3Key, fileUrl, fileSize, LocalDateTime.now());
        doc.markXmlEmbedded();
        applyRetryCount(doc, previousRetryCount);
        doc = repository.save(doc);

        pdfEventPort.publishPdfGenerated(buildGeneratedEvent(doc, command));
        sagaReplyPort.publishSuccess(
                command.getSagaId(), command.getSagaStep(), command.getCorrelationId(),
                doc.getDocumentUrl(), doc.getFileSize());

        log.info("Completed PDF generation and published events for saga {} invoice {}",
                command.getSagaId(), doc.getInvoiceNumber());
    }

    /**
     * Phase 3 (failure path): mark FAILED and atomically write the FAILURE saga reply.
     */
    @Transactional
    public void failGenerationAndPublish(UUID documentId, String errorMessage,
                                         int previousRetryCount, ProcessInvoicePdfCommand command) {
        // Normalise once — both the persisted field and the saga reply use the same value.
        String safeError = errorMessage != null ? errorMessage : "PDF generation failed";
        InvoicePdfDocument doc = requireDocument(documentId);
        doc.markFailed(safeError, LocalDateTime.now());
        applyRetryCount(doc, previousRetryCount);
        repository.save(doc);

        sagaReplyPort.publishFailure(
                command.getSagaId(), command.getSagaStep(), command.getCorrelationId(), safeError);

        log.warn("PDF generation failed for saga {} invoice {}: {}",
                command.getSagaId(), doc.getInvoiceNumber(), safeError);
    }

    /**
     * Delete a document record and flush so the DELETE precedes a subsequent INSERT
     * on the same {@code invoice_id} unique key (saga retry path).
     */
    @Transactional
    public void deleteById(UUID documentId) {
        repository.deleteById(documentId);
        repository.flush();
    }

    // -------------------------------------------------------------------------
    // Reply publishers (no document state change, outbox write only)
    // -------------------------------------------------------------------------

    /** Idempotency path: document already COMPLETED — re-publish events. */
    @Transactional
    public void publishIdempotentSuccess(InvoicePdfDocument existing,
                                         ProcessInvoicePdfCommand command) {
        pdfEventPort.publishPdfGenerated(buildGeneratedEvent(existing, command));
        sagaReplyPort.publishSuccess(
                command.getSagaId(), command.getSagaStep(), command.getCorrelationId(),
                existing.getDocumentUrl(), existing.getFileSize());
        log.warn("PDF already generated for saga {} — re-publishing SUCCESS reply",
                command.getSagaId());
    }

    /** Retry-exhausted path: send FAILURE reply without touching document state. */
    @Transactional
    public void publishRetryExhausted(ProcessInvoicePdfCommand command) {
        // Record retry exhaustion metric for monitoring upstream service issues
        if (pdfGenerationMetrics != null) {
            pdfGenerationMetrics.recordRetryExhausted(
                    command.getSagaId(), command.getDocumentId(), command.getDocumentNumber());
        }

        sagaReplyPort.publishFailure(
                command.getSagaId(), command.getSagaStep(), command.getCorrelationId(),
                "Maximum retry attempts exceeded");
        log.error("Max retries exceeded for saga {} document {}",
                command.getSagaId(), command.getDocumentNumber());
    }

    /** Generic failure before a document record was created. */
    @Transactional
    public void publishGenerationFailure(ProcessInvoicePdfCommand command, String errorMessage) {
        sagaReplyPort.publishFailure(
                command.getSagaId(), command.getSagaStep(), command.getCorrelationId(),
                errorMessage);
    }

    /** Publish COMPENSATED reply. */
    @Transactional
    public void publishCompensated(CompensateInvoicePdfCommand command) {
        sagaReplyPort.publishCompensated(
                command.getSagaId(), command.getSagaStep(), command.getCorrelationId());
    }

    /** Publish FAILURE reply when compensation itself throws. */
    @Transactional
    public void publishCompensationFailure(CompensateInvoicePdfCommand command, String error) {
        sagaReplyPort.publishFailure(
                command.getSagaId(), command.getSagaStep(), command.getCorrelationId(), error);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private InvoicePdfDocument requireDocument(UUID documentId) {
        return repository.findById(documentId)
                .orElseThrow(() -> {
                    // Log the UUID for internal diagnosis; do not forward it to callers.
                    log.error("InvoicePdfDocument not found for id={} — TX2 may have raced with compensation", documentId);
                    return new IllegalStateException("Expected invoice PDF document is absent — internal state error");
                });
    }

    private void applyRetryCount(InvoicePdfDocument doc, int previousRetryCount) {
        if (previousRetryCount < 0) return;
        doc.incrementRetryCountTo(previousRetryCount + 1);
    }

    private InvoicePdfGeneratedEvent buildGeneratedEvent(InvoicePdfDocument doc,
                                                          ProcessInvoicePdfCommand command) {
        return new InvoicePdfGeneratedEvent(
                command.getSagaId(),
                command.getDocumentId(),
                doc.getInvoiceNumber(),
                doc.getDocumentUrl(),
                doc.getFileSize(),
                doc.isXmlEmbedded(),
                command.getCorrelationId());
    }
}

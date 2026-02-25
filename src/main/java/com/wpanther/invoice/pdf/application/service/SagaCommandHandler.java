package com.wpanther.invoice.pdf.application.service;

import com.wpanther.invoice.pdf.domain.event.CompensateInvoicePdfCommand;
import com.wpanther.invoice.pdf.domain.event.InvoicePdfGeneratedEvent;
import com.wpanther.invoice.pdf.domain.event.ProcessInvoicePdfCommand;
import com.wpanther.invoice.pdf.domain.model.InvoicePdfDocument;
import com.wpanther.invoice.pdf.domain.repository.InvoicePdfDocumentRepository;
import com.wpanther.invoice.pdf.infrastructure.messaging.EventPublisher;
import com.wpanther.invoice.pdf.infrastructure.messaging.SagaReplyPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SagaCommandHandler {

    private final InvoicePdfDocumentRepository repository;
    private final InvoicePdfDocumentService pdfDocumentService;
    private final SagaReplyPublisher sagaReplyPublisher;
    private final EventPublisher eventPublisher;
    private final RestTemplate restTemplate;

    @Value("${app.pdf.generation.max-retries:3}")
    private int maxRetries;

    /**
     * Handle a ProcessInvoicePdfCommand from saga orchestrator.
     * Generates PDF and sends a SUCCESS or FAILURE reply.
     */
    @Transactional
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

                // Idempotency: already completed — re-publish events and reply SUCCESS
                if (existing.isPresent() && existing.get().isCompleted()) {
                    log.warn("Invoice PDF already generated for {}, sending SUCCESS reply",
                            command.getInvoiceNumber());
                    InvoicePdfDocument document = existing.get();
                    publishEvents(document, command);
                    sagaReplyPublisher.publishSuccess(
                            command.getSagaId(), command.getSagaStep(), command.getCorrelationId(),
                            document.getDocumentUrl(), document.getFileSize());
                    return;
                }

                // Retry limit check: use aggregate method
                if (existing.isPresent() && existing.get().isFailed()) {
                    InvoicePdfDocument failedDocument = existing.get();
                    if (failedDocument.isMaxRetriesExceeded(maxRetries)) {
                        log.error("Max retries ({}) exceeded for saga {} invoice {}",
                                maxRetries, command.getSagaId(), command.getInvoiceNumber());
                        sagaReplyPublisher.publishFailure(
                                command.getSagaId(), command.getSagaStep(), command.getCorrelationId(),
                                "Maximum retry attempts exceeded");
                        return;
                    }
                    // Delete the failed record so generatePdf() can create a fresh one;
                    // flush ensures the DELETE precedes the subsequent INSERT on the same invoiceId.
                    repository.deleteById(failedDocument.getId());
                    repository.flush();
                }

                // Validate and download signed XML from MinIO
                String signedXmlUrl = command.getSignedXmlUrl();
                if (signedXmlUrl == null || signedXmlUrl.isBlank()) {
                    throw new IllegalStateException("signedXmlUrl is null or blank in saga command");
                }
                String signedXml = restTemplate.getForObject(signedXmlUrl, String.class);
                if (signedXml == null || signedXml.isBlank()) {
                    throw new IllegalStateException("Failed to download signed XML from " + signedXmlUrl);
                }

                // Generate PDF — never throws; returns document in COMPLETED or FAILED state
                InvoicePdfDocument document = pdfDocumentService.generatePdf(
                        command.getInvoiceId(),
                        command.getInvoiceNumber(),
                        signedXml,
                        command.getInvoiceDataJson());

                // If generation failed, persist the correct retry count and send FAILURE reply
                if (document.isFailed()) {
                    carryForwardRetryCount(document, existing);
                    sagaReplyPublisher.publishFailure(
                            command.getSagaId(), command.getSagaStep(), command.getCorrelationId(),
                            document.getErrorMessage() != null
                                    ? document.getErrorMessage()
                                    : "PDF generation failed");
                    return;
                }

                // Carry forward the retry count from the previous failed attempt.
                // The new document starts at retryCount=0; set it to previousCount+1
                // so isMaxRetriesExceeded() fires correctly on the next saga retry.
                if (existing.isPresent()) {
                    carryForwardRetryCount(document, existing);
                    document = repository.save(document);
                }

                // Publish events and send SUCCESS reply with MinIO URL for subsequent steps
                publishEvents(document, command);
                sagaReplyPublisher.publishSuccess(
                        command.getSagaId(), command.getSagaStep(), command.getCorrelationId(),
                        document.getDocumentUrl(), document.getFileSize());

                log.info("Successfully processed invoice PDF generation for saga {} invoice {}",
                        command.getSagaId(), command.getInvoiceNumber());

            } catch (Exception e) {
                log.error("Failed to process invoice PDF generation for saga {} invoice {}: {}",
                        command.getSagaId(), command.getInvoiceNumber(), e.getMessage(), e);
                sagaReplyPublisher.publishFailure(
                        command.getSagaId(), command.getSagaStep(), command.getCorrelationId(),
                        describeException(e));
            }
        } finally {
            MDC.clear();
        }
    }

    /**
     * Handle a CompensateInvoicePdfCommand from saga orchestrator.
     * Deletes generated PDF document and sends a COMPENSATED reply.
     */
    @Transactional
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
                    // DB delete first: if this fails the transaction rolls back and the S3 object
                    // remains intact — no orphaned DB record pointing to a missing file.
                    // If DB delete succeeds but S3 delete fails below, we have an unreferenced S3
                    // object. That is the lesser evil: it causes no functional harm and can be
                    // cleaned up by a MinIO lifecycle expiry rule.
                    repository.deleteById(document.getId());
                    if (document.getDocumentPath() != null) {
                        pdfDocumentService.deletePdfFile(document.getDocumentPath());
                    }
                    log.info("Compensated InvoicePdfDocument {} for saga {}", document.getId(), command.getSagaId());
                } else {
                    log.info("No InvoicePdfDocument found for invoiceId {} - already compensated or never processed",
                            command.getInvoiceId());
                }

                sagaReplyPublisher.publishCompensated(
                        command.getSagaId(), command.getSagaStep(), command.getCorrelationId());

            } catch (Exception e) {
                log.error("Failed to compensate invoice PDF generation for saga {} invoice {}: {}",
                        command.getSagaId(), command.getInvoiceId(), e.getMessage(), e);
                sagaReplyPublisher.publishFailure(
                        command.getSagaId(), command.getSagaStep(), command.getCorrelationId(),
                        "Compensation failed: " + describeException(e));
            }
        } finally {
            MDC.clear();
        }
    }

    /**
     * Carry forward the retry count from the previous (deleted) failed document to the
     * replacement document. The replacement always starts at retryCount=0; incrementing
     * to previousCount+1 ensures isMaxRetriesExceeded() fires on the correct attempt.
     */
    private void carryForwardRetryCount(InvoicePdfDocument document,
                                        Optional<InvoicePdfDocument> previous) {
        if (previous.isEmpty()) {
            return;
        }
        int targetCount = previous.get().getRetryCount() + 1;
        while (document.getRetryCount() < targetCount) {
            document.incrementRetryCount();
        }
    }

    /** Returns a non-null, human-readable description of an exception for saga reply messages. */
    private String describeException(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message != null ? ": " + message : "");
    }

    private void publishEvents(InvoicePdfDocument document, ProcessInvoicePdfCommand command) {
        InvoicePdfGeneratedEvent event = new InvoicePdfGeneratedEvent(
                command.getDocumentId(),
                document.getInvoiceId(),
                document.getInvoiceNumber(),
                document.getDocumentUrl(),
                document.getFileSize(),
                document.isXmlEmbedded(),
                command.getCorrelationId()
        );
        eventPublisher.publishPdfGenerated(event);
    }
}

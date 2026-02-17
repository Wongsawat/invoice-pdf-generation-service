package com.wpanther.invoice.pdf.application.service;

import com.wpanther.invoice.pdf.domain.event.CompensateInvoicePdfCommand;
import com.wpanther.invoice.pdf.domain.event.InvoicePdfGeneratedEvent;
import com.wpanther.invoice.pdf.domain.event.ProcessInvoicePdfCommand;
import com.wpanther.invoice.pdf.domain.model.InvoicePdfDocument;
import com.wpanther.invoice.pdf.infrastructure.messaging.EventPublisher;
import com.wpanther.invoice.pdf.infrastructure.messaging.SagaReplyPublisher;
import com.wpanther.invoice.pdf.infrastructure.persistence.InvoicePdfDocumentEntity;
import com.wpanther.invoice.pdf.infrastructure.persistence.JpaInvoicePdfDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Handles saga commands from orchestrator for invoice PDF generation.
 * Delegates business logic to InvoicePdfDocumentService and sends replies.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SagaCommandHandler {

    private final JpaInvoicePdfDocumentRepository repository;
    private final InvoicePdfDocumentService pdfDocumentService;
    private final SagaReplyPublisher sagaReplyPublisher;
    private final EventPublisher eventPublisher;

    /**
     * Handle a ProcessInvoicePdfCommand from saga orchestrator.
     * Generates PDF and sends a SUCCESS or FAILURE reply.
     */
    @Transactional
    public void handleProcessCommand(ProcessInvoicePdfCommand command) {
        log.info("Handling ProcessInvoicePdfCommand for saga {} invoice {}",
                command.getSagaId(), command.getInvoiceNumber());

        try {
            // Check if already generated (idempotency)
            Optional<InvoicePdfDocumentEntity> existing =
                    repository.findByInvoiceId(command.getInvoiceId());

            if (existing.isPresent() && existing.get().getStatus() == com.wpanther.invoice.pdf.domain.model.GenerationStatus.COMPLETED) {
                log.warn("Invoice PDF already generated for {}, sending SUCCESS reply",
                        command.getInvoiceNumber());

                // Publish events for already-completed document
                InvoicePdfDocumentEntity entity = existing.get();
                publishEvents(entity, command);

                sagaReplyPublisher.publishSuccess(
                        command.getSagaId(),
                        command.getSagaStep(),
                        command.getCorrelationId()
                );
                return;
            }

            // Delete any existing failed document
            if (existing.isPresent() && existing.get().getStatus() == com.wpanther.invoice.pdf.domain.model.GenerationStatus.FAILED) {
                repository.deleteById(existing.get().getId());
                repository.flush();
            }

            // Generate PDF (calls existing service)
            InvoicePdfDocument document = pdfDocumentService.generatePdf(
                    command.getInvoiceId(),
                    command.getInvoiceNumber(),
                    command.getSignedXmlContent(),
                    command.getInvoiceDataJson()
            );

            // Publish events via outbox
            repository.findByInvoiceId(command.getInvoiceId()).ifPresent(entity ->
                    publishEvents(entity, command));

            // Send SUCCESS reply
            sagaReplyPublisher.publishSuccess(
                    command.getSagaId(),
                    command.getSagaStep(),
                    command.getCorrelationId()
            );

            log.info("Successfully processed invoice PDF generation for saga {} invoice {}",
                    command.getSagaId(), command.getInvoiceNumber());

        } catch (Exception e) {
            log.error("Failed to process invoice PDF generation for saga {} invoice {}: {}",
                    command.getSagaId(), command.getInvoiceNumber(), e.getMessage(), e);

            // Send FAILURE reply
            sagaReplyPublisher.publishFailure(
                    command.getSagaId(),
                    command.getSagaStep(),
                    command.getCorrelationId(),
                    e.getMessage()
            );
        }
    }

    /**
     * Handle a CompensateInvoicePdfCommand from saga orchestrator.
     * Deletes generated PDF document and sends a COMPENSATED reply.
     */
    @Transactional
    public void handleCompensation(CompensateInvoicePdfCommand command) {
        log.info("Handling compensation for saga {} invoice {}",
                command.getSagaId(), command.getInvoiceId());

        try {
            Optional<InvoicePdfDocumentEntity> existing =
                    repository.findByInvoiceId(command.getInvoiceId());

            if (existing.isPresent()) {
                InvoicePdfDocumentEntity entity = existing.get();
                // Delete PDF file from filesystem
                if (entity.getDocumentPath() != null) {
                    try {
                        Files.deleteIfExists(Paths.get(entity.getDocumentPath()));
                        log.info("Deleted PDF file: {}", entity.getDocumentPath());
                    } catch (Exception e) {
                        log.warn("Failed to delete PDF file: {}", entity.getDocumentPath(), e);
                    }
                }
                // Delete database record
                repository.deleteById(entity.getId());
                log.info("Deleted InvoicePdfDocument {} for compensation", entity.getId());
            } else {
                log.info("No InvoicePdfDocument found for invoiceId {} - already compensated or never processed",
                        command.getInvoiceId());
            }

            // Send COMPENSATED reply (idempotent)
            sagaReplyPublisher.publishCompensated(
                    command.getSagaId(),
                    command.getSagaStep(),
                    command.getCorrelationId()
            );

        } catch (Exception e) {
            log.error("Failed to compensate invoice PDF generation for saga {} invoice {}: {}",
                    command.getSagaId(), command.getInvoiceId(), e.getMessage(), e);

            // Send FAILURE reply
            sagaReplyPublisher.publishFailure(
                    command.getSagaId(),
                    command.getSagaStep(),
                    command.getCorrelationId(),
                    "Compensation failed: " + e.getMessage()
            );
        }
    }

    /**
     * Publish PDF generated event to notification service.
     * PDF signing is handled by the orchestrator via saga commands.
     */
    private void publishEvents(InvoicePdfDocumentEntity entity, ProcessInvoicePdfCommand command) {
        InvoicePdfGeneratedEvent event = new InvoicePdfGeneratedEvent(
                command.getDocumentId(),
                entity.getInvoiceId(),
                entity.getInvoiceNumber(),
                entity.getDocumentUrl(),
                entity.getFileSize() != null ? entity.getFileSize() : 0,
                entity.getXmlEmbedded(),
                command.getCorrelationId()
        );

        eventPublisher.publishPdfGenerated(event);
    }
}

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
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SagaCommandHandler {

    private final JpaInvoicePdfDocumentRepository repository;
    private final InvoicePdfDocumentService pdfDocumentService;
    private final SagaReplyPublisher sagaReplyPublisher;
    private final EventPublisher eventPublisher;
    private final RestTemplate restTemplate;

    @Value("${app.pdf.generation.max-retries:3}")
    private int maxRetries;

    @Transactional
    public void handleProcessCommand(ProcessInvoicePdfCommand command) {
        log.info("Handling ProcessInvoicePdfCommand for saga {} invoice {}",
                command.getSagaId(), command.getInvoiceNumber());

        try {
            Optional<InvoicePdfDocumentEntity> existing =
                    repository.findByInvoiceId(command.getInvoiceId());

            if (existing.isPresent() && existing.get().getStatus() == com.wpanther.invoice.pdf.domain.model.GenerationStatus.COMPLETED) {
                log.warn("Invoice PDF already generated for {}, sending SUCCESS reply",
                        command.getInvoiceNumber());

                InvoicePdfDocumentEntity entity = existing.get();
                publishEvents(entity, command);

                sagaReplyPublisher.publishSuccess(
                        command.getSagaId(),
                        command.getSagaStep(),
                        command.getCorrelationId(),
                        entity.getDocumentUrl(),
                        entity.getFileSize()
                );
                return;
            }

            if (existing.isPresent() && existing.get().getStatus() == com.wpanther.invoice.pdf.domain.model.GenerationStatus.FAILED) {
                InvoicePdfDocumentEntity entity = existing.get();
                int retryCount = entity.getRetryCount() != null ? entity.getRetryCount() : 0;
                if (retryCount >= maxRetries) {
                    log.error("Max retries ({}) exceeded for saga {} invoice {}",
                            maxRetries, command.getSagaId(), command.getInvoiceNumber());
                    sagaReplyPublisher.publishFailure(
                            command.getSagaId(),
                            command.getSagaStep(),
                            command.getCorrelationId(),
                            "Maximum retry attempts exceeded"
                    );
                    return;
                }
                repository.deleteById(entity.getId());
                repository.flush();
            }

            String signedXmlUrl = command.getSignedXmlUrl();
            String signedXml = restTemplate.getForObject(signedXmlUrl, String.class);
            if (signedXml == null || signedXml.isBlank()) {
                throw new IllegalStateException("Failed to download signed XML from " + signedXmlUrl);
            }

            InvoicePdfDocument document = pdfDocumentService.generatePdf(
                    command.getInvoiceId(),
                    command.getInvoiceNumber(),
                    signedXml,
                    command.getInvoiceDataJson()
            );

            if (existing.isPresent()) {
                repository.findByInvoiceId(command.getInvoiceId()).ifPresent(entity -> {
                    int retryCount = existing.get().getRetryCount() != null ? existing.get().getRetryCount() : 0;
                    entity.setRetryCount(retryCount + 1);
                    repository.save(entity);
                });
            }

            repository.findByInvoiceId(command.getInvoiceId()).ifPresent(entity ->
                    publishEvents(entity, command));

            sagaReplyPublisher.publishSuccess(
                    command.getSagaId(),
                    command.getSagaStep(),
                    command.getCorrelationId(),
                    document.getDocumentUrl(),
                    (long) document.getFileSize()
            );

            log.info("Successfully processed invoice PDF generation for saga {} invoice {}",
                    command.getSagaId(), command.getInvoiceNumber());

        } catch (Exception e) {
            log.error("Failed to process invoice PDF generation for saga {} invoice {}: {}",
                    command.getSagaId(), command.getInvoiceNumber(), e.getMessage(), e);

            sagaReplyPublisher.publishFailure(
                    command.getSagaId(),
                    command.getSagaStep(),
                    command.getCorrelationId(),
                    e.getMessage()
            );
        }
    }

    @Transactional
    public void handleCompensation(CompensateInvoicePdfCommand command) {
        log.info("Handling compensation for saga {} invoice {}",
                command.getSagaId(), command.getInvoiceId());

        try {
            Optional<InvoicePdfDocumentEntity> existing =
                    repository.findByInvoiceId(command.getInvoiceId());

            if (existing.isPresent()) {
                InvoicePdfDocumentEntity entity = existing.get();
                if (entity.getDocumentPath() != null) {
                    pdfDocumentService.deletePdfFile(entity.getDocumentPath());
                }
                repository.deleteById(entity.getId());
                log.info("Deleted InvoicePdfDocument {} for compensation", entity.getId());
            } else {
                log.info("No InvoicePdfDocument found for invoiceId {} - already compensated or never processed",
                        command.getInvoiceId());
            }

            sagaReplyPublisher.publishCompensated(
                    command.getSagaId(),
                    command.getSagaStep(),
                    command.getCorrelationId()
            );

        } catch (Exception e) {
            log.error("Failed to compensate invoice PDF generation for saga {} invoice {}: {}",
                    command.getSagaId(), command.getInvoiceId(), e.getMessage(), e);

            sagaReplyPublisher.publishFailure(
                    command.getSagaId(),
                    command.getSagaStep(),
                    command.getCorrelationId(),
                    "Compensation failed: " + e.getMessage()
            );
        }
    }

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

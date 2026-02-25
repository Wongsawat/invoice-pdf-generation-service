package com.wpanther.invoice.pdf.application.port.out;

import com.wpanther.invoice.pdf.domain.event.InvoicePdfGeneratedEvent;

/**
 * Output port for publishing PDF-related integration events.
 * Implementations write to the transactional outbox — callers must run within an active transaction.
 */
public interface PdfEventPort {

    void publishPdfGenerated(InvoicePdfGeneratedEvent event);
}

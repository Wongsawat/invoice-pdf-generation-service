package com.wpanther.invoice.pdf.application.usecase;

import com.wpanther.invoice.pdf.domain.event.CompensateInvoicePdfCommand;

public interface CompensateInvoicePdfUseCase {
    void handle(CompensateInvoicePdfCommand command);
}

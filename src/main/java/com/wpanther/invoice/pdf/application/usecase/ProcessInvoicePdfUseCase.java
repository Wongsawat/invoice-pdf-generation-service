package com.wpanther.invoice.pdf.application.usecase;

import com.wpanther.invoice.pdf.domain.event.ProcessInvoicePdfCommand;

public interface ProcessInvoicePdfUseCase {
    void handle(ProcessInvoicePdfCommand command);
}

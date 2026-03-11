package com.wpanther.invoice.pdf.infrastructure.adapter.in.kafka;

import com.wpanther.invoice.pdf.domain.event.CompensateInvoicePdfCommand;
import com.wpanther.invoice.pdf.domain.event.ProcessInvoicePdfCommand;
import org.springframework.stereotype.Component;

@Component
public class KafkaCommandMapper {

    public ProcessInvoicePdfCommand toProcess(KafkaProcessInvoicePdfCommand src) {
        return new ProcessInvoicePdfCommand(
                src.getSagaId(), src.getSagaStep(), src.getCorrelationId(),
                src.getDocumentId(), src.getInvoiceId(), src.getInvoiceNumber(),
                src.getSignedXmlUrl(), src.getInvoiceDataJson());
    }

    public CompensateInvoicePdfCommand toCompensate(KafkaCompensateInvoicePdfCommand src) {
        return new CompensateInvoicePdfCommand(
                src.getSagaId(), src.getSagaStep(), src.getCorrelationId(),
                src.getDocumentId(), src.getInvoiceId());
    }
}

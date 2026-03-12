package com.wpanther.invoice.pdf.infrastructure.adapter.in.kafka;

import com.wpanther.saga.domain.enums.SagaStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Kafka Command Classes Unit Tests")
class KafkaCommandClassesTest {

    @Test
    @DisplayName("KafkaProcessInvoicePdfCommand can be constructed with all fields")
    void kafkaProcessInvoicePdfCommand_constructor() {
        Instant now = Instant.now();
        KafkaProcessInvoicePdfCommand cmd = new KafkaProcessInvoicePdfCommand(
                java.util.UUID.randomUUID(),
                now,
                "ProcessInvoicePdfCommand",
                1,
                "saga-123",
                SagaStep.GENERATE_INVOICE_PDF,
                "corr-456",
                "doc-789",
                "inv-001",
                "INV-2024-001",
                "http://minio/signed/invoice.xml",
                "{\"invoiceNumber\":\"INV-2024-001\"}"
        );

        assertThat(cmd.getSagaId()).isEqualTo("saga-123");
        assertThat(cmd.getSagaStep()).isEqualTo(SagaStep.GENERATE_INVOICE_PDF);
        assertThat(cmd.getCorrelationId()).isEqualTo("corr-456");
        assertThat(cmd.getDocumentId()).isEqualTo("doc-789");
        assertThat(cmd.getInvoiceId()).isEqualTo("inv-001");
        assertThat(cmd.getInvoiceNumber()).isEqualTo("INV-2024-001");
        assertThat(cmd.getSignedXmlUrl()).isEqualTo("http://minio/signed/invoice.xml");
        assertThat(cmd.getInvoiceDataJson()).isEqualTo("{\"invoiceNumber\":\"INV-2024-001\"}");
    }

    @Test
    @DisplayName("KafkaCompensateInvoicePdfCommand can be constructed with all fields")
    void kafkaCompensateInvoicePdfCommand_constructor() {
        Instant now = Instant.now();
        KafkaCompensateInvoicePdfCommand cmd = new KafkaCompensateInvoicePdfCommand(
                java.util.UUID.randomUUID(),
                now,
                "CompensateInvoicePdfCommand",
                1,
                "saga-123",
                SagaStep.GENERATE_INVOICE_PDF,
                "corr-456",
                "doc-789",
                "inv-001"
        );

        assertThat(cmd.getSagaId()).isEqualTo("saga-123");
        assertThat(cmd.getSagaStep()).isEqualTo(SagaStep.GENERATE_INVOICE_PDF);
        assertThat(cmd.getCorrelationId()).isEqualTo("corr-456");
        assertThat(cmd.getDocumentId()).isEqualTo("doc-789");
        assertThat(cmd.getInvoiceId()).isEqualTo("inv-001");
    }

    @Test
    @DisplayName("KafkaCommandMapper maps Kafka commands to domain commands")
    void kafkaCommandMapper_maps() {
        KafkaCommandMapper mapper = new KafkaCommandMapper();

        KafkaProcessInvoicePdfCommand kafkaProcess = new KafkaProcessInvoicePdfCommand(
                java.util.UUID.randomUUID(),
                Instant.now(),
                "ProcessInvoicePdfCommand",
                1,
                "saga-123",
                SagaStep.GENERATE_INVOICE_PDF,
                "corr-456",
                "doc-789",
                "inv-001",
                "INV-2024-001",
                "http://minio/signed/invoice.xml",
                "{}"
        );

        var domainProcess = mapper.toProcess(kafkaProcess);
        assertThat(domainProcess.getSagaId()).isEqualTo("saga-123");

        KafkaCompensateInvoicePdfCommand kafkaCompensate = new KafkaCompensateInvoicePdfCommand(
                java.util.UUID.randomUUID(),
                Instant.now(),
                "CompensateInvoicePdfCommand",
                1,
                "saga-123",
                SagaStep.GENERATE_INVOICE_PDF,
                "corr-456",
                "doc-789",
                "inv-001"
        );

        var domainCompensate = mapper.toCompensate(kafkaCompensate);
        assertThat(domainCompensate.getSagaId()).isEqualTo("saga-123");
    }

    @Test
    @DisplayName("KafkaCommandMapper preserves all command fields during mapping")
    void kafkaCommandMapper_preservesAllFields() {
        KafkaCommandMapper mapper = new KafkaCommandMapper();

        // Test process command with all fields
        KafkaProcessInvoicePdfCommand kafkaProcess = new KafkaProcessInvoicePdfCommand(
                java.util.UUID.randomUUID(),
                Instant.now(),
                "ProcessInvoicePdfCommand",
                1,
                "saga-456",
                SagaStep.GENERATE_INVOICE_PDF,
                "corr-789",
                "doc-111",
                "inv-002",
                "INV-2025-001",
                "http://minio/signed/invoice2.xml",
                "{\"amount\":1000,\"currency\":\"THB\"}"
        );

        var domainProcess = mapper.toProcess(kafkaProcess);
        assertThat(domainProcess).isNotNull();
        assertThat(domainProcess.getSagaId()).isEqualTo("saga-456");
        assertThat(domainProcess.getInvoiceId()).isEqualTo("inv-002");
        assertThat(domainProcess.getInvoiceNumber()).isEqualTo("INV-2025-001");
        assertThat(domainProcess.getSignedXmlUrl()).isEqualTo("http://minio/signed/invoice2.xml");
        assertThat(domainProcess.getInvoiceDataJson()).isEqualTo("{\"amount\":1000,\"currency\":\"THB\"}");

        // Test compensate command
        KafkaCompensateInvoicePdfCommand kafkaCompensate = new KafkaCompensateInvoicePdfCommand(
                java.util.UUID.randomUUID(),
                Instant.now(),
                "CompensateInvoicePdfCommand",
                1,
                "saga-456",
                SagaStep.GENERATE_INVOICE_PDF,
                "corr-789",
                "doc-111",
                "inv-002"
        );

        var domainCompensate = mapper.toCompensate(kafkaCompensate);
        assertThat(domainCompensate).isNotNull();
        assertThat(domainCompensate.getSagaId()).isEqualTo("saga-456");
        assertThat(domainCompensate.getInvoiceId()).isEqualTo("inv-002");
        assertThat(domainCompensate.getDocumentId()).isEqualTo("doc-111");
    }
}

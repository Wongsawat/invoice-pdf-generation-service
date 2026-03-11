package com.wpanther.invoice.pdf.infrastructure.adapter.in.kafka;

import com.wpanther.saga.domain.enums.SagaStep;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class KafkaCommandMapperTest {

    private final KafkaCommandMapper mapper = new KafkaCommandMapper();

    @Test
    void toProcess_mapsAllFields() {
        var src = new KafkaProcessInvoicePdfCommand(
                null, null, null, 0,
                "saga-1", SagaStep.GENERATE_INVOICE_PDF, "corr-1",
                "doc-1", "inv-1", "INV-001",
                "http://minio/xml", "{\"key\":\"val\"}");

        var result = mapper.toProcess(src);

        assertThat(result.getSagaId()).isEqualTo("saga-1");
        assertThat(result.getCorrelationId()).isEqualTo("corr-1");
        assertThat(result.getDocumentId()).isEqualTo("doc-1");
        assertThat(result.getInvoiceId()).isEqualTo("inv-1");
        assertThat(result.getInvoiceNumber()).isEqualTo("INV-001");
        assertThat(result.getSignedXmlUrl()).isEqualTo("http://minio/xml");
        assertThat(result.getInvoiceDataJson()).isEqualTo("{\"key\":\"val\"}");
    }

    @Test
    void toCompensate_mapsAllFields() {
        var src = new KafkaCompensateInvoicePdfCommand(
                null, null, null, 0,
                "saga-2", SagaStep.GENERATE_INVOICE_PDF, "corr-2",
                "doc-2", "inv-2");

        var result = mapper.toCompensate(src);

        assertThat(result.getSagaId()).isEqualTo("saga-2");
        assertThat(result.getCorrelationId()).isEqualTo("corr-2");
        assertThat(result.getDocumentId()).isEqualTo("doc-2");
        assertThat(result.getInvoiceId()).isEqualTo("inv-2");
    }
}

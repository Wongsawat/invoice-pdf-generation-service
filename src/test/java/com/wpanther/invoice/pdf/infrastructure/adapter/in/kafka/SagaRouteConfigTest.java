package com.wpanther.invoice.pdf.infrastructure.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wpanther.invoice.pdf.application.service.SagaCommandHandler;
import com.wpanther.invoice.pdf.domain.event.CompensateInvoicePdfCommand;
import com.wpanther.invoice.pdf.domain.event.InvoicePdfGeneratedEvent;
import com.wpanther.invoice.pdf.domain.event.InvoicePdfReplyEvent;
import com.wpanther.invoice.pdf.domain.event.ProcessInvoicePdfCommand;
import com.wpanther.saga.domain.enums.SagaStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("SagaRouteConfig — Event Serialization Tests")
class SagaRouteConfigTest {

    @Mock
    private SagaCommandHandler sagaCommandHandler;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.configure(
                com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }

    // -------------------------------------------------------------------------
    // ProcessInvoicePdfCommand
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should serialize and deserialize ProcessInvoicePdfCommand")
    void testProcessInvoicePdfCommandSerialization() throws Exception {
        ProcessInvoicePdfCommand command = new ProcessInvoicePdfCommand(
                "saga-001", SagaStep.GENERATE_INVOICE_PDF, "corr-456",
                "doc-123", "inv-001", "INV-2024-001",
                "http://minio/signed/invoice.xml", "{}"
        );

        String json = objectMapper.writeValueAsString(command);
        ProcessInvoicePdfCommand deserialized = objectMapper.readValue(json, ProcessInvoicePdfCommand.class);

        assertThat(deserialized.getSagaId()).isEqualTo("saga-001");
        assertThat(deserialized.getSagaStep()).isEqualTo(SagaStep.GENERATE_INVOICE_PDF);
        assertThat(deserialized.getCorrelationId()).isEqualTo("corr-456");
        assertThat(deserialized.getDocumentId()).isEqualTo("doc-123");
        assertThat(deserialized.getInvoiceId()).isEqualTo("inv-001");
        assertThat(deserialized.getInvoiceNumber()).isEqualTo("INV-2024-001");
        assertThat(deserialized.getSignedXmlUrl()).isEqualTo("http://minio/signed/invoice.xml");
        assertThat(deserialized.getInvoiceDataJson()).isEqualTo("{}");
        assertThat(deserialized.getEventId()).isNotNull();
        assertThat(deserialized.getOccurredAt()).isNotNull();
    }

    @Test
    @DisplayName("Should deserialize ProcessInvoicePdfCommand from JSON with kebab-case sagaStep")
    void testProcessCommandDeserializationFromJson() throws Exception {
        String json = """
            {
                "eventId": "550e8400-e29b-41d4-a716-446655440000",
                "occurredAt": "2024-01-15T10:30:00Z",
                "eventType": "saga.command.invoice-pdf",
                "version": 1,
                "sagaId": "saga-001",
                "sagaStep": "generate-invoice-pdf",
                "correlationId": "corr-456",
                "documentId": "doc-123",
                "invoiceId": "inv-001",
                "invoiceNumber": "INV-2024-001",
                "signedXmlUrl": "http://minio/signed/invoice.xml",
                "invoiceDataJson": "{\\"key\\": \\"value\\"}"
            }
            """;

        ProcessInvoicePdfCommand cmd = objectMapper.readValue(json, ProcessInvoicePdfCommand.class);

        assertThat(cmd.getEventId()).isEqualTo(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
        assertThat(cmd.getSagaId()).isEqualTo("saga-001");
        assertThat(cmd.getSagaStep()).isEqualTo(SagaStep.GENERATE_INVOICE_PDF);
        assertThat(cmd.getInvoiceId()).isEqualTo("inv-001");
        assertThat(cmd.getInvoiceDataJson()).isEqualTo("{\"key\": \"value\"}");
    }

    // -------------------------------------------------------------------------
    // CompensateInvoicePdfCommand
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should serialize and deserialize CompensateInvoicePdfCommand")
    void testCompensateInvoicePdfCommandSerialization() throws Exception {
        CompensateInvoicePdfCommand command = new CompensateInvoicePdfCommand(
                "saga-001", SagaStep.GENERATE_INVOICE_PDF, "corr-456",
                "doc-123", "inv-001"
        );

        String json = objectMapper.writeValueAsString(command);
        CompensateInvoicePdfCommand deserialized = objectMapper.readValue(json, CompensateInvoicePdfCommand.class);

        assertThat(deserialized.getSagaId()).isEqualTo("saga-001");
        assertThat(deserialized.getSagaStep()).isEqualTo(SagaStep.GENERATE_INVOICE_PDF);
        assertThat(deserialized.getCorrelationId()).isEqualTo("corr-456");
        assertThat(deserialized.getDocumentId()).isEqualTo("doc-123");
        assertThat(deserialized.getInvoiceId()).isEqualTo("inv-001");
    }

    // -------------------------------------------------------------------------
    // InvoicePdfGeneratedEvent
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should serialize InvoicePdfGeneratedEvent with correct eventType")
    void testInvoicePdfGeneratedEventSerialization() throws Exception {
        InvoicePdfGeneratedEvent event = new InvoicePdfGeneratedEvent(
                "doc-123", "inv-001", "INV-2024-001",
                "http://minio/invoices/test.pdf", 12345L, true, "corr-456"
        );

        String json = objectMapper.writeValueAsString(event);

        assertThat(json).contains("\"eventType\":\"pdf.generated.invoice\"");
        assertThat(json).contains("\"eventId\"");
        assertThat(json).contains("\"correlationId\":\"corr-456\"");
        assertThat(json).doesNotContain("\"sagaId\":\"corr-456\"");
        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getOccurredAt()).isNotNull();
        assertThat(event.getVersion()).isEqualTo(1);
        assertThat(event.getCorrelationId()).isEqualTo("corr-456");
    }

    // -------------------------------------------------------------------------
    // InvoicePdfReplyEvent
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should create SUCCESS reply with pdfUrl and pdfSize")
    void testInvoicePdfReplyEvent_Success() throws Exception {
        InvoicePdfReplyEvent reply = InvoicePdfReplyEvent.success(
                "saga-001", SagaStep.GENERATE_INVOICE_PDF, "corr-456",
                "http://localhost:9000/invoices/test.pdf", 12345L);

        assertThat(reply.isSuccess()).isTrue();
        assertThat(reply.getSagaId()).isEqualTo("saga-001");
        assertThat(reply.getPdfUrl()).isEqualTo("http://localhost:9000/invoices/test.pdf");
        assertThat(reply.getPdfSize()).isEqualTo(12345L);

        String json = objectMapper.writeValueAsString(reply);
        assertThat(json).contains("\"sagaId\":\"saga-001\"");
        assertThat(json).contains("\"status\":\"SUCCESS\"");
        assertThat(json).contains("\"pdfUrl\"");
        assertThat(json).contains("\"pdfSize\"");
    }

    @Test
    @DisplayName("Should create FAILURE reply with errorMessage")
    void testInvoicePdfReplyEvent_Failure() throws Exception {
        InvoicePdfReplyEvent reply = InvoicePdfReplyEvent.failure(
                "saga-001", SagaStep.GENERATE_INVOICE_PDF, "corr-456", "FOP transform failed");

        assertThat(reply.isFailure()).isTrue();
        assertThat(reply.getErrorMessage()).isEqualTo("FOP transform failed");

        String json = objectMapper.writeValueAsString(reply);
        assertThat(json).contains("\"status\":\"FAILURE\"");
        assertThat(json).contains("FOP transform failed");
    }

    @Test
    @DisplayName("Should create COMPENSATED reply")
    void testInvoicePdfReplyEvent_Compensated() {
        InvoicePdfReplyEvent reply = InvoicePdfReplyEvent.compensated(
                "saga-001", SagaStep.GENERATE_INVOICE_PDF, "corr-456");

        assertThat(reply.isCompensated()).isTrue();
        assertThat(reply.getSagaId()).isEqualTo("saga-001");
        assertThat(reply.getSagaStep()).isEqualTo(SagaStep.GENERATE_INVOICE_PDF);
    }

    // -------------------------------------------------------------------------
    // DLQ saga metadata recovery
    // (tests the JSON parsing logic used by recoverAndNotifyOrchestrator)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("DLQ recovery: raw JSON bytes yield saga coordinates via JsonNode.path().asText(null)")
    void dlqRecovery_rawJsonBytes_sagaCoordinatesExtractable() throws Exception {
        byte[] rawBytes = """
            {"sagaId":"saga-001","sagaStep":"generate-invoice-pdf","correlationId":"corr-456","invoiceId":"inv-001"}
            """.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        JsonNode node = objectMapper.readTree(rawBytes);

        assertThat(node.path("sagaId").asText(null)).isEqualTo("saga-001");
        assertThat(node.path("sagaStep").asText(null)).isEqualTo("generate-invoice-pdf");
        assertThat(node.path("correlationId").asText(null)).isEqualTo("corr-456");

        // Verify the extracted kebab-case sagaStep can be round-tripped to the enum
        SagaStep step = objectMapper.readValue(
                "\"" + node.path("sagaStep").asText() + "\"", SagaStep.class);
        assertThat(step).isEqualTo(SagaStep.GENERATE_INVOICE_PDF);
    }

    @Test
    @DisplayName("DLQ recovery: missing saga fields return null from JsonNode.path().asText(null)")
    void dlqRecovery_missingSagaMetadata_asTextNullReturnsNull() throws Exception {
        byte[] rawBytes = """
            {"someOtherField":"value"}
            """.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        JsonNode node = objectMapper.readTree(rawBytes);

        assertThat(node.path("sagaId").asText(null)).isNull();
        assertThat(node.path("sagaStep").asText(null)).isNull();
        assertThat(node.path("correlationId").asText(null)).isNull();
    }

    @Test
    @DisplayName("SagaStep GENERATE_INVOICE_PDF serializes to kebab-case code")
    void testSagaStepSerialization() throws Exception {
        String json = objectMapper.writeValueAsString(SagaStep.GENERATE_INVOICE_PDF);
        assertThat(json).isEqualTo("\"generate-invoice-pdf\"");

        SagaStep step = objectMapper.readValue("\"generate-invoice-pdf\"", SagaStep.class);
        assertThat(step).isEqualTo(SagaStep.GENERATE_INVOICE_PDF);
    }

    // -------------------------------------------------------------------------
    // Full @JsonCreator constructor coverage
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should deserialize CompensateInvoicePdfCommand from full JSON (exercises @JsonCreator constructor)")
    void testCompensateCommandDeserializationFromJson() throws Exception {
        String json = """
            {
                "eventId": "550e8400-e29b-41d4-a716-446655440001",
                "occurredAt": "2024-01-15T10:30:00Z",
                "eventType": "saga.compensation.invoice-pdf",
                "version": 1,
                "sagaId": "saga-002",
                "sagaStep": "generate-invoice-pdf",
                "correlationId": "corr-789",
                "documentId": "doc-456",
                "invoiceId": "inv-002"
            }
            """;

        CompensateInvoicePdfCommand cmd = objectMapper.readValue(json, CompensateInvoicePdfCommand.class);

        assertThat(cmd.getSagaId()).isEqualTo("saga-002");
        assertThat(cmd.getSagaStep()).isEqualTo(SagaStep.GENERATE_INVOICE_PDF);
        assertThat(cmd.getCorrelationId()).isEqualTo("corr-789");
        assertThat(cmd.getDocumentId()).isEqualTo("doc-456");
        assertThat(cmd.getInvoiceId()).isEqualTo("inv-002");
    }

    @Test
    @DisplayName("Should deserialize InvoicePdfGeneratedEvent from full JSON (exercises @JsonCreator constructor)")
    void testInvoicePdfGeneratedEventDeserializationFromJson() throws Exception {
        String json = """
            {
                "eventId": "550e8400-e29b-41d4-a716-446655440002",
                "occurredAt": "2024-01-15T11:00:00Z",
                "eventType": "pdf.generated.invoice",
                "version": 1,
                "sagaId": null,
                "source": "invoice-pdf-generation-service",
                "traceType": "PDF_GENERATED",
                "context": null,
                "documentId": "doc-123",
                "invoiceId": "inv-001",
                "invoiceNumber": "INV-2024-001",
                "documentUrl": "http://localhost:9001/invoices/test.pdf",
                "fileSize": 123456,
                "xmlEmbedded": true,
                "correlationId": "corr-456"
            }
            """;

        InvoicePdfGeneratedEvent event = objectMapper.readValue(json, InvoicePdfGeneratedEvent.class);

        assertThat(event.getDocumentId()).isEqualTo("doc-123");
        assertThat(event.getInvoiceId()).isEqualTo("inv-001");
        assertThat(event.getInvoiceNumber()).isEqualTo("INV-2024-001");
        assertThat(event.getDocumentUrl()).isEqualTo("http://localhost:9001/invoices/test.pdf");
        assertThat(event.getFileSize()).isEqualTo(123456L);
        assertThat(event.isXmlEmbedded()).isTrue();
        assertThat(event.getCorrelationId()).isEqualTo("corr-456");
        assertThat(event.getEventType()).isEqualTo("pdf.generated.invoice");
    }
}

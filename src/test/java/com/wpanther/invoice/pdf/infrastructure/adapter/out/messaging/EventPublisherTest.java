package com.wpanther.invoice.pdf.infrastructure.adapter.out.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.invoice.pdf.domain.event.InvoicePdfGeneratedEvent;
import com.wpanther.saga.domain.model.TraceEvent;
import com.wpanther.saga.infrastructure.outbox.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("EventPublisher Unit Tests")
class EventPublisherTest {

    @Mock
    private OutboxService outboxService;

    private EventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new EventPublisher(outboxService, new ObjectMapper(), "pdf.generated.invoice");
    }

    private InvoicePdfGeneratedEvent buildEvent() {
        return new InvoicePdfGeneratedEvent(
                "saga-001", "doc-123", "inv-001", "INV-2024-001",
                "http://localhost:9001/invoices/test.pdf", 12345L, true, "corr-456"
        );
    }

    @Test
    @DisplayName("publishPdfGenerated() routes to configured pdf-generated topic")
    void publishPdfGenerated_callsOutboxWithCorrectTopic() {
        InvoicePdfGeneratedEvent event = buildEvent();

        publisher.publishPdfGenerated(event);

        verify(outboxService).saveWithRouting(
                eq(event),
                eq("InvoicePdfDocument"),
                eq("inv-001"),
                eq("pdf.generated.invoice"),
                eq("inv-001"),
                any()
        );
    }

    @Test
    @DisplayName("publishPdfGenerated() headers contain documentType=INVOICE and correlationId")
    void publishPdfGenerated_headersContainDocumentTypeAndCorrelationId() {
        InvoicePdfGeneratedEvent event = buildEvent();
        ArgumentCaptor<String> headersCaptor = ArgumentCaptor.forClass(String.class);

        publisher.publishPdfGenerated(event);

        verify(outboxService).saveWithRouting(any(), any(), any(), any(), any(), headersCaptor.capture());
        String headers = headersCaptor.getValue();
        assertThat(headers).contains("documentType").contains("INVOICE");
        assertThat(headers).contains("correlationId").contains("corr-456");
    }

    @Test
    @DisplayName("publishPdfGenerated() uses invoiceId as both aggregateId and partitionKey")
    void publishPdfGenerated_usesInvoiceIdAsAggregateIdAndPartitionKey() {
        InvoicePdfGeneratedEvent event = buildEvent();
        ArgumentCaptor<String> aggregateIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> partitionKeyCaptor = ArgumentCaptor.forClass(String.class);

        publisher.publishPdfGenerated(event);

        verify(outboxService).saveWithRouting(
                any(), any(),
                aggregateIdCaptor.capture(),
                any(),
                partitionKeyCaptor.capture(),
                any()
        );
        assertThat(aggregateIdCaptor.getValue()).isEqualTo("inv-001");
        assertThat(partitionKeyCaptor.getValue()).isEqualTo("inv-001");
    }

    @Test
    @DisplayName("InvoicePdfGeneratedEvent correlationId is accessible via polymorphic TraceEvent reference")
    void getCorrelationId_polymorphicAccessorShouldReturnProvidedValue() {
        // Arrange
        InvoicePdfGeneratedEvent event = new InvoicePdfGeneratedEvent(
                "saga-001", "doc-123", "inv-001", "INV-2024-001",
                "http://localhost:9001/invoices/test.pdf", 12345L, true,
                "corr-abc");

        // Act — call via polymorphic TraceEvent reference
        TraceEvent traceEvent = event;

        // Assert
        assertThat(traceEvent.getCorrelationId()).isEqualTo("corr-abc");
    }
}

package com.wpanther.invoice.pdf.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.invoice.pdf.domain.event.InvoicePdfReplyEvent;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.saga.infrastructure.outbox.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("SagaReplyPublisher Unit Tests")
class SagaReplyPublisherTest {

    @Mock
    private OutboxService outboxService;

    private SagaReplyPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new SagaReplyPublisher(outboxService, new ObjectMapper());
        ReflectionTestUtils.setField(publisher, "replyTopic", "saga.reply.invoice-pdf");
    }

    // -------------------------------------------------------------------------
    // publishSuccess
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("publishSuccess() routes to configured saga reply topic")
    void publishSuccess_callsOutboxWithCorrectTopic() {
        publisher.publishSuccess("saga-001", SagaStep.GENERATE_INVOICE_PDF, "corr-456",
                "http://minio/invoices/test.pdf", 12345L);

        verify(outboxService).saveWithRouting(
                any(InvoicePdfReplyEvent.class),
                eq("InvoicePdfDocument"),
                eq("saga-001"),
                eq("saga.reply.invoice-pdf"),
                eq("saga-001"),
                any()
        );
    }

    @Test
    @DisplayName("publishSuccess() publishes a SUCCESS reply with pdfUrl and pdfSize")
    void publishSuccess_replyContainsPdfUrlAndPdfSize() {
        ArgumentCaptor<InvoicePdfReplyEvent> eventCaptor = ArgumentCaptor.forClass(InvoicePdfReplyEvent.class);

        publisher.publishSuccess("saga-001", SagaStep.GENERATE_INVOICE_PDF, "corr-456",
                "http://minio/invoices/test.pdf", 12345L);

        verify(outboxService).saveWithRouting(eventCaptor.capture(), any(), any(), any(), any(), any());
        InvoicePdfReplyEvent reply = eventCaptor.getValue();
        assertThat(reply.isSuccess()).isTrue();
        assertThat(reply.getPdfUrl()).isEqualTo("http://minio/invoices/test.pdf");
        assertThat(reply.getPdfSize()).isEqualTo(12345L);
    }

    @Test
    @DisplayName("publishSuccess() headers contain sagaId, correlationId, and SUCCESS status")
    void publishSuccess_headersContainCorrectMetadata() {
        ArgumentCaptor<String> headersCaptor = ArgumentCaptor.forClass(String.class);

        publisher.publishSuccess("saga-001", SagaStep.GENERATE_INVOICE_PDF, "corr-456",
                "http://minio/invoices/test.pdf", 12345L);

        verify(outboxService).saveWithRouting(any(), any(), any(), any(), any(), headersCaptor.capture());
        String headers = headersCaptor.getValue();
        assertThat(headers).contains("sagaId").contains("saga-001");
        assertThat(headers).contains("correlationId").contains("corr-456");
        assertThat(headers).contains("status").contains("SUCCESS");
    }

    // -------------------------------------------------------------------------
    // publishFailure
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("publishFailure() publishes a FAILURE reply with the error message")
    void publishFailure_replyContainsFailureStatusAndErrorMessage() {
        ArgumentCaptor<InvoicePdfReplyEvent> eventCaptor = ArgumentCaptor.forClass(InvoicePdfReplyEvent.class);

        publisher.publishFailure("saga-001", SagaStep.GENERATE_INVOICE_PDF, "corr-456", "FOP failed");

        verify(outboxService).saveWithRouting(eventCaptor.capture(), any(), any(), any(), any(), any());
        InvoicePdfReplyEvent reply = eventCaptor.getValue();
        assertThat(reply.isFailure()).isTrue();
        assertThat(reply.getErrorMessage()).isEqualTo("FOP failed");
    }

    @Test
    @DisplayName("publishFailure() headers contain FAILURE status")
    void publishFailure_headersContainFailureStatus() {
        ArgumentCaptor<String> headersCaptor = ArgumentCaptor.forClass(String.class);

        publisher.publishFailure("saga-001", SagaStep.GENERATE_INVOICE_PDF, "corr-456", "FOP failed");

        verify(outboxService).saveWithRouting(any(), any(), any(), any(), any(), headersCaptor.capture());
        assertThat(headersCaptor.getValue()).contains("FAILURE");
    }

    // -------------------------------------------------------------------------
    // publishCompensated
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("publishCompensated() publishes a COMPENSATED reply")
    void publishCompensated_replyHasCompensatedStatus() {
        ArgumentCaptor<InvoicePdfReplyEvent> eventCaptor = ArgumentCaptor.forClass(InvoicePdfReplyEvent.class);

        publisher.publishCompensated("saga-001", SagaStep.GENERATE_INVOICE_PDF, "corr-456");

        verify(outboxService).saveWithRouting(eventCaptor.capture(), any(), any(), any(), any(), any());
        assertThat(eventCaptor.getValue().isCompensated()).isTrue();
    }

    @Test
    @DisplayName("publishCompensated() headers contain COMPENSATED status")
    void publishCompensated_headersContainCompensatedStatus() {
        ArgumentCaptor<String> headersCaptor = ArgumentCaptor.forClass(String.class);

        publisher.publishCompensated("saga-001", SagaStep.GENERATE_INVOICE_PDF, "corr-456");

        verify(outboxService).saveWithRouting(any(), any(), any(), any(), any(), headersCaptor.capture());
        assertThat(headersCaptor.getValue()).contains("COMPENSATED");
    }
}

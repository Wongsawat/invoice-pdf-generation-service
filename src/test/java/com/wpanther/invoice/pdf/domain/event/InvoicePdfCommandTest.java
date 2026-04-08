package com.wpanther.invoice.pdf.domain.event;

import com.wpanther.saga.domain.enums.SagaStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Invoice PDF Command null-guard tests")
class InvoicePdfCommandTest {

    @Test
    @DisplayName("ProcessInvoicePdfCommand rejects null documentId")
    void processCommand_nullDocumentId_throwsNullPointerException() {
        assertThatThrownBy(() ->
                new ProcessInvoicePdfCommand("saga-1", SagaStep.GENERATE_INVOICE_PDF, "corr-1",
                        null, "INV-001", "http://localhost/signed.xml", "{}"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("documentId");
    }

    @Test
    @DisplayName("ProcessInvoicePdfCommand rejects null documentNumber")
    void processCommand_nullDocumentNumber_throwsNullPointerException() {
        assertThatThrownBy(() ->
                new ProcessInvoicePdfCommand("saga-1", SagaStep.GENERATE_INVOICE_PDF, "corr-1",
                        "doc-1", null, "http://localhost/signed.xml", "{}"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("documentNumber");
    }

    @Test
    @DisplayName("ProcessInvoicePdfCommand rejects null signedXmlUrl")
    void processCommand_nullSignedXmlUrl_throwsNullPointerException() {
        assertThatThrownBy(() ->
                new ProcessInvoicePdfCommand("saga-1", SagaStep.GENERATE_INVOICE_PDF, "corr-1",
                        "doc-1", "INV-001", null, "{}"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("signedXmlUrl");
    }

    @Test
    @DisplayName("ProcessInvoicePdfCommand rejects null invoiceDataJson")
    void processCommand_nullInvoiceDataJson_throwsNullPointerException() {
        assertThatThrownBy(() ->
                new ProcessInvoicePdfCommand("saga-1", SagaStep.GENERATE_INVOICE_PDF, "corr-1",
                        "doc-1", "INV-001", "http://localhost/signed.xml", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("invoiceDataJson");
    }

    @Test
    @DisplayName("ProcessInvoicePdfCommand accepts valid arguments")
    void processCommand_validArgs_constructsSuccessfully() {
        ProcessInvoicePdfCommand cmd = new ProcessInvoicePdfCommand(
                "saga-1", SagaStep.GENERATE_INVOICE_PDF, "corr-1",
                "doc-1", "INV-001", "http://localhost/signed.xml", "{}");
        org.assertj.core.api.Assertions.assertThat(cmd.getDocumentId()).isEqualTo("doc-1");
        org.assertj.core.api.Assertions.assertThat(cmd.getDocumentNumber()).isEqualTo("INV-001");
    }

    @Test
    @DisplayName("CompensateInvoicePdfCommand rejects null documentId")
    void compensateCommand_nullDocumentId_throwsNullPointerException() {
        assertThatThrownBy(() ->
                new CompensateInvoicePdfCommand("saga-1", SagaStep.GENERATE_INVOICE_PDF, "corr-1",
                        null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("documentId");
    }

    @Test
    @DisplayName("CompensateInvoicePdfCommand accepts valid arguments")
    void compensateCommand_validArgs_constructsSuccessfully() {
        CompensateInvoicePdfCommand cmd = new CompensateInvoicePdfCommand(
                "saga-1", SagaStep.GENERATE_INVOICE_PDF, "corr-1", "doc-1");
        org.assertj.core.api.Assertions.assertThat(cmd.getDocumentId()).isEqualTo("doc-1");
    }
}

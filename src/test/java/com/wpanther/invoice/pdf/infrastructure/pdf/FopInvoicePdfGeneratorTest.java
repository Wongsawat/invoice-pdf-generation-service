package com.wpanther.invoice.pdf.infrastructure.pdf;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FopInvoicePdfGenerator Unit Tests")
class FopInvoicePdfGeneratorTest {

    private FopInvoicePdfGenerator generator;

    @BeforeEach
    void setUp() throws Exception {
        // Constructor loads FOP config (falls back to default if missing — no exception)
        generator = new FopInvoicePdfGenerator();
    }

    @Test
    @DisplayName("Non-existent XSL path → PdfGenerationException with descriptive message")
    void generatePdf_missingXslPath_throwsPdfGenerationException() {
        String xml = "<invoice><invoiceNumber>INV-001</invoiceNumber></invoice>";

        assertThatThrownBy(() -> generator.generatePdf(xml, "xsl/nonexistent-template.xsl"))
                .isInstanceOf(FopInvoicePdfGenerator.PdfGenerationException.class)
                .hasMessageContaining("XSL template not found");
    }

    @Test
    @DisplayName("Blank XSL path → PdfGenerationException")
    void generatePdf_blankXslPath_throwsPdfGenerationException() {
        String xml = "<invoice><invoiceNumber>INV-001</invoiceNumber></invoice>";

        assertThatThrownBy(() -> generator.generatePdf(xml, ""))
                .isInstanceOf(FopInvoicePdfGenerator.PdfGenerationException.class);
    }
}

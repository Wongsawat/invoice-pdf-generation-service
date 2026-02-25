package com.wpanther.invoice.pdf.infrastructure.pdf;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PdfA3Converter Unit Tests")
class PdfA3ConverterTest {

    private PdfA3Converter converter;

    @BeforeEach
    void setUp() {
        converter = new PdfA3Converter();
    }

    @Test
    @DisplayName("Invalid PDF bytes → PdfConversionException with descriptive message")
    void convertToPdfA3_invalidPdfBytes_throwsPdfConversionException() {
        byte[] invalidBytes = "this is not a valid pdf document".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() ->
                converter.convertToPdfA3(invalidBytes, "<invoice/>", "invoice.xml", "INV-001"))
                .isInstanceOf(PdfA3Converter.PdfConversionException.class)
                .hasMessageContaining("PDF/A-3 conversion failed");
    }

    @Test
    @DisplayName("Empty PDF bytes → PdfConversionException")
    void convertToPdfA3_emptyBytes_throwsPdfConversionException() {
        assertThatThrownBy(() ->
                converter.convertToPdfA3(new byte[0], "<invoice/>", "invoice.xml", "INV-001"))
                .isInstanceOf(PdfA3Converter.PdfConversionException.class);
    }

    @Test
    @DisplayName("Random binary garbage → PdfConversionException")
    void convertToPdfA3_randomBytes_throwsPdfConversionException() {
        byte[] garbage = new byte[]{0x00, 0x01, 0x02, (byte) 0xFF, (byte) 0xFE};

        assertThatThrownBy(() ->
                converter.convertToPdfA3(garbage, "<invoice/>", "invoice.xml", "INV-999"))
                .isInstanceOf(PdfA3Converter.PdfConversionException.class);
    }
}

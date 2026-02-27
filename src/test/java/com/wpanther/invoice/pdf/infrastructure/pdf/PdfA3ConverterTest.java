package com.wpanther.invoice.pdf.infrastructure.pdf;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@DisplayName("PdfA3Converter Unit Tests")
class PdfA3ConverterTest {

    private PdfA3Converter converter;

    @BeforeEach
    void setUp() {
        converter = new PdfA3Converter("icc/sRGB.icc", new SimpleMeterRegistry());
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Valid PDF → PDF/A-3 output with embedded XML attachment and output intent")
    void convertToPdfA3_validPdf_producesPdfA3WithEmbeddedXml() throws Exception {
        // Build a minimal PDF using PDFBox (single blank page)
        byte[] minimalPdf;
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(bos);
            minimalPdf = bos.toByteArray();
        }

        byte[] result = converter.convertToPdfA3(
                minimalPdf, "<invoice/>", "invoice.xml", "INV-001");

        assertThat(result).isNotEmpty();
        // Verify the output is still a valid PDF and the XML attachment is present
        try (PDDocument doc = Loader.loadPDF(result)) {
            assertThat(doc.getDocumentCatalog().getNames().getEmbeddedFiles().getNames())
                    .containsKey("invoice.xml");
            // Output intent (ICC color profile) should be present for PDF/A compliance
            assertThat(doc.getDocumentCatalog().getOutputIntents()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("Embedded XML content is preserved verbatim in the attachment")
    void convertToPdfA3_xmlContentPreserved() throws Exception {
        byte[] minimalPdf;
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(bos);
            minimalPdf = bos.toByteArray();
        }
        String xmlContent = "<invoice><number>INV-2024-001</number></invoice>";

        byte[] result = converter.convertToPdfA3(minimalPdf, xmlContent, "invoice.xml", "INV-001");

        try (PDDocument doc = Loader.loadPDF(result)) {
            var embeddedFile = doc.getDocumentCatalog()
                    .getNames().getEmbeddedFiles().getNames().get("invoice.xml")
                    .getEmbeddedFile();
            String extractedXml = new String(embeddedFile.toByteArray(), StandardCharsets.UTF_8);
            assertThat(extractedXml).isEqualTo(xmlContent);
        }
    }

    // -------------------------------------------------------------------------
    // Error paths
    // -------------------------------------------------------------------------

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

    @Test
    @DisplayName("PDF that already has an output intent skips ICC profile re-add")
    void convertToPdfA3_pdfWithExistingOutputIntent_skipsAddingColorProfile() throws Exception {
        byte[] minimalPdf;
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(bos);
            minimalPdf = bos.toByteArray();
        }
        // First conversion adds an output intent
        byte[] pdfWithIntent = converter.convertToPdfA3(minimalPdf, "<invoice/>", "invoice.xml", "INV-A");
        // Second conversion on the already-converted PDF: output intent already exists → early return path
        byte[] result = converter.convertToPdfA3(pdfWithIntent, "<invoice/>", "invoice2.xml", "INV-B");

        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("Null ICC profile bytes → color profile setup is skipped gracefully")
    void convertToPdfA3_nullIccProfile_skipsColorProfileSetup() throws Exception {
        byte[] minimalPdf;
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(bos);
            minimalPdf = bos.toByteArray();
        }
        // Force iccProfileBytes to null to exercise the null-check guard
        ReflectionTestUtils.setField(converter, "iccProfileBytes", null);

        assertThatCode(() -> converter.convertToPdfA3(minimalPdf, "<invoice/>", "invoice.xml", "INV-C"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Constructor with non-existent ICC path throws IllegalStateException (fail-fast)")
    void constructor_nonExistentIccProfilePath_throwsIllegalStateException() {
        assertThatThrownBy(() -> new PdfA3Converter("icc/does-not-exist.icc", new SimpleMeterRegistry()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("icc/does-not-exist.icc");
    }

    // -------------------------------------------------------------------------
    // Exception constructor coverage
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("PdfConversionException(String) 1-arg constructor carries the message")
    void pdfConversionException_messageOnlyConstructor_hasMessage() {
        var ex = new PdfA3Converter.PdfConversionException("test failure");
        assertThat(ex.getMessage()).isEqualTo("test failure");
        assertThat(ex.getCause()).isNull();
    }
}

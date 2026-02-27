package com.wpanther.invoice.pdf.infrastructure.pdf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.invoice.pdf.domain.service.InvoicePdfGenerationService.InvoicePdfGenerationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("InvoicePdfGenerationServiceImpl Unit Tests")
class InvoicePdfGenerationServiceImplTest {

    @Mock private FopInvoicePdfGenerator fopGenerator;
    @Mock private PdfA3Converter pdfA3Converter;

    private InvoicePdfGenerationServiceImpl service;

    private static final String INVOICE_NUMBER = "INV-2024-001";
    private static final String SIGNED_XML = "<Invoice>signed</Invoice>";
    private static final byte[] BASE_PDF = {0x25, 0x50, 0x44, 0x46}; // %PDF header
    private static final byte[] PDFA3_BYTES = {0x25, 0x50, 0x44, 0x46, 0x2D, 0x41}; // %PDF-A

    private static final String FULL_JSON = """
            {
              "invoiceNumber": "INV-2024-001",
              "invoiceDate": "2024-01-15",
              "dueDate": "2024-02-15",
              "seller": {
                "name": "Seller Co Ltd",
                "taxId": "1234567890123",
                "address": "123 Main St"
              },
              "buyer": {
                "name": "Buyer Co Ltd",
                "taxId": "9876543210987",
                "address": "456 Other St"
              },
              "lineItems": [
                { "description": "Widget", "quantity": "2", "unitPrice": "500", "amount": "1000" }
              ],
              "subtotal": "1000",
              "vatAmount": "70",
              "grandTotal": "1070"
            }
            """;

    @BeforeEach
    void setUp() {
        service = new InvoicePdfGenerationServiceImpl(fopGenerator, pdfA3Converter, new ObjectMapper(), "7", 1048576);
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Happy path: valid JSON → FOP → PDFBox → PDF/A-3 bytes returned")
    void generatePdf_happyPath_returnsPdfA3Bytes() throws Exception {
        when(fopGenerator.generatePdf(anyString())).thenReturn(BASE_PDF);
        when(pdfA3Converter.convertToPdfA3(eq(BASE_PDF), eq(SIGNED_XML), anyString(), eq(INVOICE_NUMBER)))
                .thenReturn(PDFA3_BYTES);

        byte[] result = service.generatePdf(INVOICE_NUMBER, SIGNED_XML, FULL_JSON);

        assertThat(result).isEqualTo(PDFA3_BYTES);
        verify(fopGenerator).generatePdf(anyString());
        verify(pdfA3Converter).convertToPdfA3(
                eq(BASE_PDF), eq(SIGNED_XML),
                eq("invoice-" + INVOICE_NUMBER + ".xml"),
                eq(INVOICE_NUMBER));
    }

    // -------------------------------------------------------------------------
    // JSON → XML conversion content
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Invoice number appears in generated XML passed to FOP")
    void generatePdf_xmlContainsInvoiceNumber() throws Exception {
        when(fopGenerator.generatePdf(anyString())).thenReturn(BASE_PDF);
        when(pdfA3Converter.convertToPdfA3(any(), any(), any(), any())).thenReturn(PDFA3_BYTES);

        ArgumentCaptor<String> xmlCaptor = ArgumentCaptor.forClass(String.class);
        service.generatePdf(INVOICE_NUMBER, SIGNED_XML, FULL_JSON);

        verify(fopGenerator).generatePdf(xmlCaptor.capture());
        assertThat(xmlCaptor.getValue()).contains("<invoiceNumber>INV-2024-001</invoiceNumber>");
    }

    @Test
    @DisplayName("Seller and buyer names appear in generated XML")
    void generatePdf_xmlContainsSellerAndBuyer() throws Exception {
        when(fopGenerator.generatePdf(anyString())).thenReturn(BASE_PDF);
        when(pdfA3Converter.convertToPdfA3(any(), any(), any(), any())).thenReturn(PDFA3_BYTES);

        ArgumentCaptor<String> xmlCaptor = ArgumentCaptor.forClass(String.class);
        service.generatePdf(INVOICE_NUMBER, SIGNED_XML, FULL_JSON);

        verify(fopGenerator).generatePdf(xmlCaptor.capture());
        String xml = xmlCaptor.getValue();
        assertThat(xml)
                .contains("<name>Seller Co Ltd</name>")
                .contains("<name>Buyer Co Ltd</name>")
                .contains("<taxId>1234567890123</taxId>");
    }

    @Test
    @DisplayName("Line items appear in generated XML")
    void generatePdf_xmlContainsLineItems() throws Exception {
        when(fopGenerator.generatePdf(anyString())).thenReturn(BASE_PDF);
        when(pdfA3Converter.convertToPdfA3(any(), any(), any(), any())).thenReturn(PDFA3_BYTES);

        ArgumentCaptor<String> xmlCaptor = ArgumentCaptor.forClass(String.class);
        service.generatePdf(INVOICE_NUMBER, SIGNED_XML, FULL_JSON);

        verify(fopGenerator).generatePdf(xmlCaptor.capture());
        String xml = xmlCaptor.getValue();
        assertThat(xml)
                .contains("<description>Widget</description>")
                .contains("<amount>1000</amount>");
    }

    @Test
    @DisplayName("XML special characters are escaped: & < >  (single quotes are valid unescaped in text content)")
    void generatePdf_specialCharsEscapedInXml() throws Exception {
        String jsonWithSpecialChars = """
                {
                  "invoiceNumber": "INV-001",
                  "seller": { "name": "A & B <Co> 'Ltd'" }
                }
                """;
        when(fopGenerator.generatePdf(anyString())).thenReturn(BASE_PDF);
        when(pdfA3Converter.convertToPdfA3(any(), any(), any(), any())).thenReturn(PDFA3_BYTES);

        ArgumentCaptor<String> xmlCaptor = ArgumentCaptor.forClass(String.class);
        service.generatePdf("INV-001", SIGNED_XML, jsonWithSpecialChars);

        verify(fopGenerator).generatePdf(xmlCaptor.capture());
        String xml = xmlCaptor.getValue();
        // XMLStreamWriter.writeCharacters() escapes & and < but leaves > and ' unescaped
        // (only < and & are required to be escaped in element text content per XML spec)
        assertThat(xml)
                .contains("&amp;")              // & must be escaped
                .contains("&lt;")               // < must be escaped
                .contains("Co>")                // > is left as-is (valid in text content)
                .contains("'Ltd'")              // ' is left as-is (valid in text content)
                .doesNotContain("A & B <Co>");  // unescaped & and < must not appear
    }

    @Test
    @DisplayName("Empty JSON object → no exception; FOP is still called")
    void generatePdf_emptyJson_noException() throws Exception {
        when(fopGenerator.generatePdf(anyString())).thenReturn(BASE_PDF);
        when(pdfA3Converter.convertToPdfA3(any(), any(), any(), any())).thenReturn(PDFA3_BYTES);

        assertThatCode(() -> service.generatePdf(INVOICE_NUMBER, SIGNED_XML, "{}"))
                .doesNotThrowAnyException();
        verify(fopGenerator).generatePdf(anyString());
    }

    // -------------------------------------------------------------------------
    // xmlContent guards
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Null xmlContent → InvoicePdfGenerationException before FOP")
    void generatePdf_nullXmlContent_throwsException() {
        assertThatThrownBy(() -> service.generatePdf(INVOICE_NUMBER, null, FULL_JSON))
                .isInstanceOf(InvoicePdfGenerationException.class)
                .hasMessageContaining("xmlContent");
        verifyNoInteractions(fopGenerator, pdfA3Converter);
    }

    @Test
    @DisplayName("Blank xmlContent → InvoicePdfGenerationException before FOP")
    void generatePdf_blankXmlContent_throwsException() {
        assertThatThrownBy(() -> service.generatePdf(INVOICE_NUMBER, "   ", FULL_JSON))
                .isInstanceOf(InvoicePdfGenerationException.class)
                .hasMessageContaining("xmlContent");
        verifyNoInteractions(fopGenerator, pdfA3Converter);
    }

    // -------------------------------------------------------------------------
    // M2 fix: invalid JSON must propagate, not silently degrade
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("M2 fix: invalid JSON → InvoicePdfGenerationException (no silent degradation)")
    void generatePdf_invalidJson_throwsException() {
        assertThatThrownBy(() ->
                service.generatePdf(INVOICE_NUMBER, SIGNED_XML, "{ not valid json ["))
                .isInstanceOf(InvoicePdfGenerationException.class);
        verifyNoInteractions(fopGenerator, pdfA3Converter);
    }

    @Test
    @DisplayName("M2 fix: JSON payload exceeding max size → InvoicePdfGenerationException before parsing")
    void generatePdf_jsonExceedsMaxSize_throwsException() {
        // Build a service with a tiny 10-byte limit
        InvoicePdfGenerationServiceImpl smallLimitService =
                new InvoicePdfGenerationServiceImpl(fopGenerator, pdfA3Converter, new ObjectMapper(), "7", 10);

        assertThatThrownBy(() ->
                smallLimitService.generatePdf(INVOICE_NUMBER, SIGNED_XML, FULL_JSON))
                .isInstanceOf(InvoicePdfGenerationException.class)
                .hasMessageContaining("exceeds max allowed size");
        verifyNoInteractions(fopGenerator, pdfA3Converter);
    }

    // -------------------------------------------------------------------------
    // Exception wrapping
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("FOP throws PdfGenerationException → wrapped in InvoicePdfGenerationException")
    void generatePdf_fopThrows_wrappedAsInvoicePdfGenerationException() throws Exception {
        when(fopGenerator.generatePdf(anyString()))
                .thenThrow(new FopInvoicePdfGenerator.PdfGenerationException("FOP failed"));

        assertThatThrownBy(() -> service.generatePdf(INVOICE_NUMBER, SIGNED_XML, FULL_JSON))
                .isInstanceOf(InvoicePdfGenerationException.class)
                .hasMessageContaining("PDF generation failed")
                .hasMessageContaining("FOP failed");
        verify(pdfA3Converter, never()).convertToPdfA3(any(), any(), any(), any());
    }

    @Test
    @DisplayName("PDFBox throws PdfConversionException → wrapped in InvoicePdfGenerationException")
    void generatePdf_pdfboxThrows_wrappedAsInvoicePdfGenerationException() throws Exception {
        when(fopGenerator.generatePdf(anyString())).thenReturn(BASE_PDF);
        when(pdfA3Converter.convertToPdfA3(any(), any(), any(), any()))
                .thenThrow(new PdfA3Converter.PdfConversionException("PDFBox failed"));

        assertThatThrownBy(() -> service.generatePdf(INVOICE_NUMBER, SIGNED_XML, FULL_JSON))
                .isInstanceOf(InvoicePdfGenerationException.class)
                .hasMessageContaining("PDF/A-3 conversion failed")
                .hasMessageContaining("PDFBox failed");
    }

    @Test
    @DisplayName("JSON with paymentInfo block → paymentInfo elements appear in generated XML")
    void generatePdf_jsonWithPaymentInfo_xmlContainsPaymentInfo() throws Exception {
        String jsonWithPaymentInfo = """
                {
                  "invoiceNumber": "INV-2024-002",
                  "seller": { "name": "Seller Co" },
                  "paymentInfo": {
                    "method": "Bank Transfer",
                    "bankName": "Bangkok Bank",
                    "accountNumber": "123-4-56789-0",
                    "accountName": "Seller Co Ltd"
                  }
                }
                """;
        when(fopGenerator.generatePdf(anyString())).thenReturn(BASE_PDF);
        when(pdfA3Converter.convertToPdfA3(any(), any(), any(), any())).thenReturn(PDFA3_BYTES);

        ArgumentCaptor<String> xmlCaptor = ArgumentCaptor.forClass(String.class);
        service.generatePdf("INV-2024-002", SIGNED_XML, jsonWithPaymentInfo);

        verify(fopGenerator).generatePdf(xmlCaptor.capture());
        String xml = xmlCaptor.getValue();
        assertThat(xml)
                .contains("<paymentInfo>")
                .contains("<method>Bank Transfer</method>")
                .contains("<bankName>Bangkok Bank</bankName>")
                .contains("<accountNumber>123-4-56789-0</accountNumber>")
                .contains("<accountName>Seller Co Ltd</accountName>")
                .contains("</paymentInfo>");
    }
}

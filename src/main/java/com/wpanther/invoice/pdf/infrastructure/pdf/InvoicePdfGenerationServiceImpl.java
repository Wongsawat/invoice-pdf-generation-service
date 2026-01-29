package com.wpanther.invoice.pdf.infrastructure.pdf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.invoice.pdf.domain.service.InvoicePdfGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Implementation of InvoicePdfGenerationService using Apache FOP and PDFBox.
 *
 * This service:
 * 1. Converts invoice JSON data to XML format for XSL-FO processing
 * 2. Generates base PDF using Apache FOP with XSL-FO template
 * 3. Converts to PDF/A-3 format using PDFBox
 * 4. Embeds the original XML as an attachment
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InvoicePdfGenerationServiceImpl implements InvoicePdfGenerationService {

    private final FopInvoicePdfGenerator fopPdfGenerator;
    private final PdfA3Converter pdfA3Converter;
    private final ObjectMapper objectMapper;

    @Override
    public byte[] generatePdf(String invoiceNumber, String xmlContent, String invoiceDataJson)
            throws InvoicePdfGenerationException {

        log.info("Starting PDF generation for invoice: {}", invoiceNumber);

        try {
            // Step 1: Convert invoice JSON to XML for FOP processing
            String invoiceXml = convertJsonToXml(invoiceDataJson, invoiceNumber);
            log.debug("Converted invoice data to XML format");

            // Step 2: Generate base PDF using FOP
            byte[] basePdf = fopPdfGenerator.generatePdf(invoiceXml);
            log.debug("Generated base PDF: {} bytes", basePdf.length);

            // Step 3: Convert to PDF/A-3 and embed original XML
            String xmlFilename = "invoice-" + invoiceNumber + ".xml";
            byte[] pdfA3 = pdfA3Converter.convertToPdfA3(basePdf, xmlContent, xmlFilename, invoiceNumber);
            log.info("Generated PDF/A-3 for invoice {}: {} bytes", invoiceNumber, pdfA3.length);

            return pdfA3;

        } catch (FopInvoicePdfGenerator.PdfGenerationException e) {
            log.error("FOP PDF generation failed for invoice: {}", invoiceNumber, e);
            throw new InvoicePdfGenerationException("PDF generation failed: " + e.getMessage(), e);
        } catch (PdfA3Converter.PdfConversionException e) {
            log.error("PDF/A-3 conversion failed for invoice: {}", invoiceNumber, e);
            throw new InvoicePdfGenerationException("PDF/A-3 conversion failed: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error during PDF generation for invoice: {}", invoiceNumber, e);
            throw new InvoicePdfGenerationException("PDF generation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Convert invoice JSON data to XML format for XSL-FO processing.
     */
    private String convertJsonToXml(String invoiceDataJson, String invoiceNumber) throws Exception {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<invoice>\n");

        try {
            JsonNode root = objectMapper.readTree(invoiceDataJson);

            // Invoice header
            appendElement(xml, "invoiceNumber", getTextValue(root, "invoiceNumber", invoiceNumber));
            appendElement(xml, "invoiceDate", getTextValue(root, "invoiceDate", ""));
            appendElement(xml, "dueDate", getTextValue(root, "dueDate", ""));
            appendElement(xml, "documentType", getTextValue(root, "documentType", "Invoice"));
            appendElement(xml, "purchaseOrderNumber", getTextValue(root, "purchaseOrderNumber", ""));

            // Seller information
            xml.append("  <seller>\n");
            JsonNode seller = root.path("seller");
            appendElement(xml, "name", getTextValue(seller, "name", ""), 4);
            appendElement(xml, "taxId", getTextValue(seller, "taxId", ""), 4);
            appendElement(xml, "branchId", getTextValue(seller, "branchId", ""), 4);
            appendElement(xml, "branchName", getTextValue(seller, "branchName", ""), 4);
            appendElement(xml, "address", getTextValue(seller, "address", ""), 4);
            appendElement(xml, "phone", getTextValue(seller, "phone", ""), 4);
            appendElement(xml, "email", getTextValue(seller, "email", ""), 4);
            xml.append("  </seller>\n");

            // Buyer information
            xml.append("  <buyer>\n");
            JsonNode buyer = root.path("buyer");
            appendElement(xml, "name", getTextValue(buyer, "name", ""), 4);
            appendElement(xml, "taxId", getTextValue(buyer, "taxId", ""), 4);
            appendElement(xml, "branchId", getTextValue(buyer, "branchId", ""), 4);
            appendElement(xml, "branchName", getTextValue(buyer, "branchName", ""), 4);
            appendElement(xml, "address", getTextValue(buyer, "address", ""), 4);
            appendElement(xml, "phone", getTextValue(buyer, "phone", ""), 4);
            appendElement(xml, "email", getTextValue(buyer, "email", ""), 4);
            xml.append("  </buyer>\n");

            // Line items
            xml.append("  <lineItems>\n");
            JsonNode lineItems = root.path("lineItems");
            if (lineItems.isArray()) {
                for (JsonNode item : lineItems) {
                    xml.append("    <item>\n");
                    appendElement(xml, "itemCode", getTextValue(item, "itemCode", ""), 6);
                    appendElement(xml, "description", getTextValue(item, "description", ""), 6);
                    appendElement(xml, "quantity", getTextValue(item, "quantity", "0"), 6);
                    appendElement(xml, "unit", getTextValue(item, "unit", ""), 6);
                    appendElement(xml, "unitPrice", getTextValue(item, "unitPrice", "0"), 6);
                    appendElement(xml, "amount", getTextValue(item, "amount", "0"), 6);
                    xml.append("    </item>\n");
                }
            }
            xml.append("  </lineItems>\n");

            // Totals
            appendElement(xml, "subtotal", getTextValue(root, "subtotal", "0"));
            appendElement(xml, "discount", getTextValue(root, "discount", "0"));
            appendElement(xml, "amountBeforeVat", getTextValue(root, "amountBeforeVat", "0"));
            appendElement(xml, "vatRate", getTextValue(root, "vatRate", "7"));
            appendElement(xml, "vatAmount", getTextValue(root, "vatAmount", "0"));
            appendElement(xml, "grandTotal", getTextValue(root, "grandTotal", "0"));
            appendElement(xml, "amountInWords", getTextValue(root, "amountInWords", ""));

            // Payment information (optional)
            JsonNode paymentInfo = root.path("paymentInfo");
            if (!paymentInfo.isMissingNode()) {
                xml.append("  <paymentInfo>\n");
                appendElement(xml, "method", getTextValue(paymentInfo, "method", ""), 4);
                appendElement(xml, "bankName", getTextValue(paymentInfo, "bankName", ""), 4);
                appendElement(xml, "accountNumber", getTextValue(paymentInfo, "accountNumber", ""), 4);
                appendElement(xml, "accountName", getTextValue(paymentInfo, "accountName", ""), 4);
                xml.append("  </paymentInfo>\n");
            }

            // Notes
            appendElement(xml, "notes", getTextValue(root, "notes", ""));

        } catch (Exception e) {
            log.warn("Failed to parse invoice JSON, using minimal XML: {}", e.getMessage());
            // Provide minimal XML if JSON parsing fails
            appendElement(xml, "invoiceNumber", invoiceNumber);
        }

        xml.append("</invoice>\n");
        return xml.toString();
    }

    private String getTextValue(JsonNode node, String fieldName, String defaultValue) {
        JsonNode field = node.path(fieldName);
        if (field.isMissingNode() || field.isNull()) {
            return defaultValue;
        }
        return field.asText(defaultValue);
    }

    private void appendElement(StringBuilder xml, String name, String value) {
        appendElement(xml, name, value, 2);
    }

    private void appendElement(StringBuilder xml, String name, String value, int indent) {
        if (value != null && !value.isEmpty()) {
            xml.append(" ".repeat(indent))
               .append("<").append(name).append(">")
               .append(escapeXml(value))
               .append("</").append(name).append(">\n");
        }
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }
}

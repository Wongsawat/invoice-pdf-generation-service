package com.wpanther.invoice.pdf.infrastructure.pdf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.invoice.pdf.domain.service.InvoicePdfGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.StringWriter;

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
     * Uses XMLStreamWriter for correct automatic escaping of XML special characters.
     */
    private String convertJsonToXml(String invoiceDataJson, String invoiceNumber) throws Exception {
        XMLOutputFactory factory = XMLOutputFactory.newInstance();
        StringWriter sw = new StringWriter();
        XMLStreamWriter writer = factory.createXMLStreamWriter(sw);

        try {
            JsonNode root = objectMapper.readTree(invoiceDataJson);

            writer.writeStartDocument("UTF-8", "1.0");
            writer.writeStartElement("invoice");

            // Invoice header
            writeElement(writer, "invoiceNumber", getTextValue(root, "invoiceNumber", invoiceNumber));
            writeElement(writer, "invoiceDate",        getTextValue(root, "invoiceDate", ""));
            writeElement(writer, "dueDate",            getTextValue(root, "dueDate", ""));
            writeElement(writer, "documentType",       getTextValue(root, "documentType", "Invoice"));
            writeElement(writer, "purchaseOrderNumber", getTextValue(root, "purchaseOrderNumber", ""));

            // Seller information
            writer.writeStartElement("seller");
            JsonNode seller = root.path("seller");
            writeElement(writer, "name",       getTextValue(seller, "name", ""));
            writeElement(writer, "taxId",      getTextValue(seller, "taxId", ""));
            writeElement(writer, "branchId",   getTextValue(seller, "branchId", ""));
            writeElement(writer, "branchName", getTextValue(seller, "branchName", ""));
            writeElement(writer, "address",    getTextValue(seller, "address", ""));
            writeElement(writer, "phone",      getTextValue(seller, "phone", ""));
            writeElement(writer, "email",      getTextValue(seller, "email", ""));
            writer.writeEndElement(); // seller

            // Buyer information
            writer.writeStartElement("buyer");
            JsonNode buyer = root.path("buyer");
            writeElement(writer, "name",       getTextValue(buyer, "name", ""));
            writeElement(writer, "taxId",      getTextValue(buyer, "taxId", ""));
            writeElement(writer, "branchId",   getTextValue(buyer, "branchId", ""));
            writeElement(writer, "branchName", getTextValue(buyer, "branchName", ""));
            writeElement(writer, "address",    getTextValue(buyer, "address", ""));
            writeElement(writer, "phone",      getTextValue(buyer, "phone", ""));
            writeElement(writer, "email",      getTextValue(buyer, "email", ""));
            writer.writeEndElement(); // buyer

            // Line items
            writer.writeStartElement("lineItems");
            JsonNode lineItems = root.path("lineItems");
            if (lineItems.isArray()) {
                for (JsonNode item : lineItems) {
                    writer.writeStartElement("item");
                    writeElement(writer, "itemCode",    getTextValue(item, "itemCode", ""));
                    writeElement(writer, "description", getTextValue(item, "description", ""));
                    writeElement(writer, "quantity",    getTextValue(item, "quantity", "0"));
                    writeElement(writer, "unit",        getTextValue(item, "unit", ""));
                    writeElement(writer, "unitPrice",   getTextValue(item, "unitPrice", "0"));
                    writeElement(writer, "amount",      getTextValue(item, "amount", "0"));
                    writer.writeEndElement(); // item
                }
            }
            writer.writeEndElement(); // lineItems

            // Totals
            writeElement(writer, "subtotal",        getTextValue(root, "subtotal", "0"));
            writeElement(writer, "discount",        getTextValue(root, "discount", "0"));
            writeElement(writer, "amountBeforeVat", getTextValue(root, "amountBeforeVat", "0"));
            writeElement(writer, "vatRate",         getTextValue(root, "vatRate", "7"));
            writeElement(writer, "vatAmount",       getTextValue(root, "vatAmount", "0"));
            writeElement(writer, "grandTotal",      getTextValue(root, "grandTotal", "0"));
            writeElement(writer, "amountInWords",   getTextValue(root, "amountInWords", ""));

            // Payment information (optional)
            JsonNode paymentInfo = root.path("paymentInfo");
            if (!paymentInfo.isMissingNode()) {
                writer.writeStartElement("paymentInfo");
                writeElement(writer, "method",        getTextValue(paymentInfo, "method", ""));
                writeElement(writer, "bankName",      getTextValue(paymentInfo, "bankName", ""));
                writeElement(writer, "accountNumber", getTextValue(paymentInfo, "accountNumber", ""));
                writeElement(writer, "accountName",   getTextValue(paymentInfo, "accountName", ""));
                writer.writeEndElement(); // paymentInfo
            }

            // Notes
            writeElement(writer, "notes", getTextValue(root, "notes", ""));

            writer.writeEndElement(); // invoice
            writer.writeEndDocument();
            writer.flush();

        } catch (Exception e) {
            log.error("Failed to parse invoice JSON for invoice {}: {}", invoiceNumber, e.getMessage());
            throw e;  // propagate — generatePdf wraps this in InvoicePdfGenerationException
        } finally {
            writer.close();
        }

        return sw.toString();
    }

    /**
     * Write a non-empty text element. Skipped when value is null or blank —
     * consistent with the XSL-FO template which uses xsl:if to handle absent fields.
     * XMLStreamWriter.writeCharacters() automatically escapes &, <, > in text content.
     */
    private void writeElement(XMLStreamWriter writer, String name, String value) throws XMLStreamException {
        if (value != null && !value.isEmpty()) {
            writer.writeStartElement(name);
            writer.writeCharacters(value);
            writer.writeEndElement();
        }
    }

    private String getTextValue(JsonNode node, String fieldName, String defaultValue) {
        JsonNode field = node.path(fieldName);
        if (field.isMissingNode() || field.isNull()) {
            return defaultValue;
        }
        return field.asText(defaultValue);
    }
}

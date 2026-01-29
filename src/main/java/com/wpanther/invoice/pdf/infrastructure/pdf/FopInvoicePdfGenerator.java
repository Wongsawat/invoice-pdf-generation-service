package com.wpanther.invoice.pdf.infrastructure.pdf;

import lombok.extern.slf4j.Slf4j;
import org.apache.fop.apps.Fop;
import org.apache.fop.apps.FopFactory;
import org.apache.fop.apps.MimeConstants;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.stream.StreamSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Generates PDF documents using Apache FOP with XSL-FO templates.
 *
 * This class transforms XML invoice data using an XSL-FO stylesheet
 * to produce PDF output.
 */
@Component
@Slf4j
public class FopInvoicePdfGenerator {

    private static final String FOP_CONFIG_PATH = "fop/fop.xconf";
    private static final String INVOICE_XSL_PATH = "xsl/invoice.xsl";

    private final FopFactory fopFactory;
    private final TransformerFactory transformerFactory;

    public FopInvoicePdfGenerator() throws Exception {
        this.fopFactory = createFopFactory();
        this.transformerFactory = TransformerFactory.newInstance();
        log.info("FopInvoicePdfGenerator initialized with config: {}", FOP_CONFIG_PATH);
    }

    private FopFactory createFopFactory() throws Exception {
        try {
            ClassPathResource configResource = new ClassPathResource(FOP_CONFIG_PATH);
            if (configResource.exists()) {
                try (InputStream configStream = configResource.getInputStream()) {
                    URI baseUri = new File(".").toURI();
                    return FopFactory.newInstance(baseUri, configStream);
                }
            } else {
                log.warn("FOP config not found at {}, using default configuration", FOP_CONFIG_PATH);
                return FopFactory.newInstance(new File(".").toURI());
            }
        } catch (Exception e) {
            log.warn("Failed to load FOP config, using default: {}", e.getMessage());
            return FopFactory.newInstance(new File(".").toURI());
        }
    }

    /**
     * Generate PDF from XML data using the invoice XSL-FO template.
     *
     * @param xmlData The XML representation of invoice data
     * @return PDF bytes
     * @throws PdfGenerationException if generation fails
     */
    public byte[] generatePdf(String xmlData) throws PdfGenerationException {
        return generatePdf(xmlData, INVOICE_XSL_PATH);
    }

    /**
     * Generate PDF from XML data using a specified XSL-FO template.
     *
     * @param xmlData The XML representation of invoice data
     * @param xslPath Path to the XSL-FO template (classpath resource)
     * @return PDF bytes
     * @throws PdfGenerationException if generation fails
     */
    public byte[] generatePdf(String xmlData, String xslPath) throws PdfGenerationException {
        log.debug("Generating PDF with template: {}", xslPath);

        try (ByteArrayOutputStream pdfOutput = new ByteArrayOutputStream()) {
            // Load XSL template
            ClassPathResource xslResource = new ClassPathResource(xslPath);
            if (!xslResource.exists()) {
                throw new PdfGenerationException("XSL template not found: " + xslPath);
            }

            // Create FOP instance
            Fop fop = fopFactory.newFop(MimeConstants.MIME_PDF, pdfOutput);

            // Setup transformer with XSL
            Source xslSource = new StreamSource(xslResource.getInputStream());
            Transformer transformer = transformerFactory.newTransformer(xslSource);

            // Setup input XML
            Source xmlSource = new StreamSource(
                new ByteArrayInputStream(xmlData.getBytes(StandardCharsets.UTF_8))
            );

            // Setup output
            Result result = new SAXResult(fop.getDefaultHandler());

            // Transform
            transformer.transform(xmlSource, result);

            byte[] pdfBytes = pdfOutput.toByteArray();
            log.info("Generated PDF: {} bytes", pdfBytes.length);

            return pdfBytes;

        } catch (Exception e) {
            log.error("Failed to generate PDF", e);
            throw new PdfGenerationException("PDF generation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Exception thrown when PDF generation fails
     */
    public static class PdfGenerationException extends Exception {
        public PdfGenerationException(String message) {
            super(message);
        }

        public PdfGenerationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

package com.wpanther.invoice.pdf.infrastructure.pdf;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.apache.xmpbox.XMPMetadata;
import org.apache.xmpbox.schema.DublinCoreSchema;
import org.apache.xmpbox.schema.PDFAIdentificationSchema;
import org.apache.xmpbox.schema.XMPBasicSchema;
import org.apache.xmpbox.xml.XmpSerializer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.Collections;
import java.util.GregorianCalendar;

/**
 * Converts PDF documents to PDF/A-3 format and embeds XML attachments.
 *
 * PDF/A-3 is an ISO standard for long-term archiving of electronic documents
 * that allows embedding of arbitrary file formats (including XML).
 */
@Component
@Slf4j
public class PdfA3Converter {

    private static final String ICC_PROFILE_PATH = "icc/sRGB.icc";
    private static final String MIME_TYPE_XML = "application/xml";
    private static final String AFRelationship_SOURCE = "Source";

    /**
     * Convert PDF to PDF/A-3b format with embedded XML.
     *
     * @param pdfBytes      The source PDF bytes
     * @param xmlContent    The XML content to embed
     * @param xmlFilename   The filename for the embedded XML (e.g., "invoice.xml")
     * @param invoiceNumber The invoice number for metadata
     * @return PDF/A-3 compliant PDF bytes with embedded XML
     * @throws PdfConversionException if conversion fails
     */
    public byte[] convertToPdfA3(byte[] pdfBytes, String xmlContent, String xmlFilename, String invoiceNumber)
            throws PdfConversionException {

        log.debug("Converting PDF to PDF/A-3 with embedded XML: {}", xmlFilename);

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {

            // Add PDF/A-3 identification metadata
            addPdfAMetadata(document, invoiceNumber);

            // Add ICC color profile (required for PDF/A)
            addColorProfile(document);

            // Embed the XML file
            embedXmlFile(document, xmlContent, xmlFilename);

            // Write the result
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                document.save(output);
                byte[] result = output.toByteArray();
                log.info("Converted to PDF/A-3: {} bytes (XML embedded: {})", result.length, xmlFilename);
                return result;
            }

        } catch (Exception e) {
            log.error("Failed to convert PDF to PDF/A-3", e);
            throw new PdfConversionException("PDF/A-3 conversion failed: " + e.getMessage(), e);
        }
    }

    /**
     * Add PDF/A-3b identification metadata using XMP.
     */
    private void addPdfAMetadata(PDDocument document, String invoiceNumber) throws Exception {
        XMPMetadata xmp = XMPMetadata.createXMPMetadata();

        // PDF/A identification
        PDFAIdentificationSchema pdfaId = xmp.createAndAddPDFAIdentificationSchema();
        pdfaId.setPart(3);  // PDF/A-3
        pdfaId.setConformance("B");  // Level B (basic)

        // Dublin Core metadata
        DublinCoreSchema dc = xmp.createAndAddDublinCoreSchema();
        dc.setTitle("Thai e-Tax Invoice: " + invoiceNumber);
        dc.setDescription("PDF/A-3 Invoice document with embedded XML");
        dc.addCreator("Invoice PDF Generation Service");
        dc.setFormat("application/pdf");

        // XMP Basic metadata
        XMPBasicSchema xmpBasic = xmp.createAndAddXMPBasicSchema();
        Calendar now = GregorianCalendar.from(LocalDateTime.now().atZone(ZoneId.systemDefault()));
        xmpBasic.setCreateDate(now);
        xmpBasic.setModifyDate(now);
        xmpBasic.setCreatorTool("Thai e-Tax Invoice System");

        // Serialize XMP to document
        XmpSerializer serializer = new XmpSerializer();
        ByteArrayOutputStream xmpOutput = new ByteArrayOutputStream();
        serializer.serialize(xmp, xmpOutput, true);

        PDMetadata metadata = new PDMetadata(document);
        metadata.importXMPMetadata(xmpOutput.toByteArray());

        PDDocumentCatalog catalog = document.getDocumentCatalog();
        catalog.setMetadata(metadata);
    }

    /**
     * Add sRGB ICC color profile (required for PDF/A compliance).
     */
    private void addColorProfile(PDDocument document) throws Exception {
        PDDocumentCatalog catalog = document.getDocumentCatalog();

        // Check if output intent already exists
        if (catalog.getOutputIntents() != null && !catalog.getOutputIntents().isEmpty()) {
            log.debug("Output intent already exists, skipping ICC profile");
            return;
        }

        // Load ICC profile
        InputStream iccStream = null;
        try {
            ClassPathResource iccResource = new ClassPathResource(ICC_PROFILE_PATH);
            if (iccResource.exists()) {
                iccStream = iccResource.getInputStream();
            } else {
                // Use built-in sRGB profile as fallback
                log.warn("ICC profile not found at {}, using system default", ICC_PROFILE_PATH);
                iccStream = PdfA3Converter.class.getResourceAsStream("/org/apache/pdfbox/resources/icc/ISOcoated_v2_300_bas.icc");
            }

            if (iccStream != null) {
                PDOutputIntent outputIntent = new PDOutputIntent(document, iccStream);
                outputIntent.setInfo("sRGB IEC61966-2.1");
                outputIntent.setOutputCondition("sRGB");
                outputIntent.setOutputConditionIdentifier("sRGB IEC61966-2.1");
                outputIntent.setRegistryName("http://www.color.org");
                catalog.addOutputIntent(outputIntent);
                log.debug("Added sRGB ICC color profile");
            }
        } finally {
            if (iccStream != null) {
                iccStream.close();
            }
        }
    }

    /**
     * Embed XML file as an attachment in the PDF.
     */
    private void embedXmlFile(PDDocument document, String xmlContent, String xmlFilename) throws Exception {
        PDDocumentCatalog catalog = document.getDocumentCatalog();

        // Create embedded file
        byte[] xmlBytes = xmlContent.getBytes(StandardCharsets.UTF_8);
        PDEmbeddedFile embeddedFile = new PDEmbeddedFile(document, new ByteArrayInputStream(xmlBytes));
        embeddedFile.setSubtype(MIME_TYPE_XML);
        embeddedFile.setSize(xmlBytes.length);
        embeddedFile.setCreationDate(new GregorianCalendar());
        embeddedFile.setModDate(new GregorianCalendar());

        // Create file specification
        PDComplexFileSpecification fileSpec = new PDComplexFileSpecification();
        fileSpec.setFile(xmlFilename);
        fileSpec.setFileUnicode(xmlFilename);
        fileSpec.setEmbeddedFile(embeddedFile);
        fileSpec.setEmbeddedFileUnicode(embeddedFile);

        // Set AFRelationship to "Source" (PDF/A-3 requirement for source data)
        fileSpec.getCOSObject().setName(COSName.getPDFName("AFRelationship"), AFRelationship_SOURCE);

        // Add to document's embedded files
        PDEmbeddedFilesNameTreeNode embeddedFilesTree = new PDEmbeddedFilesNameTreeNode();
        embeddedFilesTree.setNames(Collections.singletonMap(xmlFilename, fileSpec));

        // Get or create name dictionary
        PDDocumentNameDictionary nameDictionary = catalog.getNames();
        if (nameDictionary == null) {
            nameDictionary = new PDDocumentNameDictionary(catalog);
            catalog.setNames(nameDictionary);
        }
        nameDictionary.setEmbeddedFiles(embeddedFilesTree);

        // Add to AF array (Associated Files - PDF/A-3 requirement)
        catalog.getCOSObject().setItem(COSName.getPDFName("AF"), fileSpec);

        log.debug("Embedded XML file: {} ({} bytes)", xmlFilename, xmlBytes.length);
    }

    /**
     * Exception thrown when PDF conversion fails
     */
    public static class PdfConversionException extends Exception {
        public PdfConversionException(String message) {
            super(message);
        }

        public PdfConversionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

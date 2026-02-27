package com.wpanther.invoice.pdf.infrastructure.pdf;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.annotation.NewSpan;
import lombok.extern.slf4j.Slf4j;
import org.apache.fop.apps.Fop;
import org.apache.fop.apps.FopFactory;
import org.apache.fop.apps.MimeConstants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.xml.transform.Templates;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Generates PDF documents using Apache FOP with XSL-FO templates.
 *
 * This class transforms XML invoice data using an XSL-FO stylesheet
 * to produce PDF output.
 *
 * Thread-safety: the XSL template is pre-compiled once at startup into a
 * {@link Templates} object (which is thread-safe by the JAXP contract).
 * A fair {@link Semaphore} caps the number of concurrent FOP render jobs.
 */
@Component
@Slf4j
public class FopInvoicePdfGenerator {

    private static final String FOP_CONFIG_PATH = "fop/fop.xconf";
    private static final String INVOICE_XSL_PATH = "xsl/invoice.xsl";

    private final FopFactory fopFactory;
    private final Templates compiledTemplate;   // thread-safe compiled XSL
    private final Semaphore renderSemaphore;    // caps concurrent FOP renders
    private final Timer renderTimer;
    private final DistributionSummary pdfSizeSummary;

    private final long maxPdfSizeBytes;

    public FopInvoicePdfGenerator(
            @Value("${app.pdf.generation.max-concurrent-renders:3}") int maxConcurrentRenders,
            @Value("${app.pdf.generation.max-pdf-size-bytes:52428800}") long maxPdfSizeBytes,
            MeterRegistry meterRegistry) {
        if (maxConcurrentRenders < 1) {
            throw new IllegalStateException(
                    "app.pdf.generation.max-concurrent-renders must be >= 1, got: " + maxConcurrentRenders);
        }
        if (maxPdfSizeBytes < 1) {
            throw new IllegalStateException(
                    "app.pdf.generation.max-pdf-size-bytes must be >= 1, got: " + maxPdfSizeBytes);
        }
        this.maxPdfSizeBytes = maxPdfSizeBytes;
        try {
            this.fopFactory = createFopFactory();

            // TransformerFactory used ONLY here (single-threaded at startup) — not retained
            TransformerFactory tf = TransformerFactory.newInstance();
            ClassPathResource xslResource = new ClassPathResource(INVOICE_XSL_PATH);
            if (!xslResource.exists()) {
                throw new IllegalStateException("XSL template not found: " + INVOICE_XSL_PATH);
            }
            this.compiledTemplate = tf.newTemplates(new StreamSource(xslResource.getInputStream()));
            this.renderSemaphore  = new Semaphore(maxConcurrentRenders, true); // fair

            this.renderTimer = meterRegistry.timer("pdf.fop.render");
            this.pdfSizeSummary = DistributionSummary.builder("pdf.fop.size.bytes")
                    .description("Size of generated invoice PDFs in bytes")
                    .register(meterRegistry);
            Gauge.builder("pdf.fop.render.available_permits", renderSemaphore, Semaphore::availablePermits)
                    .description("Available FOP concurrent render permits")
                    .register(meterRegistry);

            log.info("FopInvoicePdfGenerator initialized: maxConcurrentRenders={} maxPdfSizeBytes={} (each FOP render ~50–200 MB heap)",
                    maxConcurrentRenders, maxPdfSizeBytes);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize FOP PDF generator", e);
        }
    }

    private FopFactory createFopFactory() throws Exception {
        ClassPathResource configResource = new ClassPathResource(FOP_CONFIG_PATH);
        if (configResource.exists()) {
            // Config file is present — fail fast if it is malformed rather than silently
            // falling back to FOP defaults (which would produce PDFs with wrong fonts).
            try (InputStream configStream = configResource.getInputStream()) {
                return FopFactory.newInstance(new File(".").toURI(), configStream);
            }
        }
        log.warn("FOP config not found at {}, using default configuration", FOP_CONFIG_PATH);
        return FopFactory.newInstance(new File(".").toURI());
    }

    /**
     * Generate PDF from XML data using the pre-compiled invoice XSL-FO template.
     *
     * @param xmlData The XML representation of invoice data
     * @return PDF bytes
     * @throws PdfGenerationException if generation fails
     */
    @NewSpan("pdf.fop.render")
    public byte[] generatePdf(String xmlData) throws PdfGenerationException {
        log.debug("Awaiting render permit (available={})", renderSemaphore.availablePermits());
        try {
            renderSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PdfGenerationException("PDF generation interrupted while waiting for render slot", e);
        }
        long t0 = System.nanoTime();
        try (ByteArrayOutputStream pdfOutput = new ByteArrayOutputStream()) {
            Fop fop = fopFactory.newFop(MimeConstants.MIME_PDF, pdfOutput);
            Transformer transformer = compiledTemplate.newTransformer(); // per-call, thread-safe
            StreamSource xmlSource = new StreamSource(
                new ByteArrayInputStream(xmlData.getBytes(StandardCharsets.UTF_8)));
            transformer.transform(xmlSource, new SAXResult(fop.getDefaultHandler()));
            byte[] pdfBytes = pdfOutput.toByteArray();
            if (pdfBytes.length > maxPdfSizeBytes) {
                throw new PdfGenerationException(
                        String.format("Generated PDF exceeds max allowed size: %d bytes > %d bytes",
                                pdfBytes.length, maxPdfSizeBytes));
            }
            log.info("Generated PDF: {} bytes", pdfBytes.length);
            pdfSizeSummary.record(pdfBytes.length);
            return pdfBytes;
        } catch (Exception e) {
            log.error("Failed to generate PDF", e);
            throw new PdfGenerationException("PDF generation failed: " + e.getMessage(), e);
        } finally {
            renderTimer.record(System.nanoTime() - t0, TimeUnit.NANOSECONDS);
            renderSemaphore.release();
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

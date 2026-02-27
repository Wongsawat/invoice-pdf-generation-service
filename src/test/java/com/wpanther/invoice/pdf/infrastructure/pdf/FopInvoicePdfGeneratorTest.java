package com.wpanther.invoice.pdf.infrastructure.pdf;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FopInvoicePdfGenerator Unit Tests")
class FopInvoicePdfGeneratorTest {

    @Test
    @DisplayName("Constructor succeeds and compiles XSL template")
    void constructor_compilesTemplateSuccessfully() {
        // No exception = template found and compiled
        assertThatCode(() -> new FopInvoicePdfGenerator(2, new SimpleMeterRegistry()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Constructor rejects maxConcurrentRenders < 1 with IllegalStateException")
    void constructor_invalidMaxConcurrentRenders_throwsIllegalStateException() {
        assertThatThrownBy(() -> new FopInvoicePdfGenerator(0, new SimpleMeterRegistry()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-concurrent-renders")
                .hasMessageContaining("0");
    }

    @Test
    @DisplayName("Semaphore is initialised with the configured permit count")
    void constructor_semaphorePermitsMatchConfiguration() throws Exception {
        FopInvoicePdfGenerator gen = new FopInvoicePdfGenerator(5, new SimpleMeterRegistry());
        Field f = FopInvoicePdfGenerator.class.getDeclaredField("renderSemaphore");
        f.setAccessible(true);
        Semaphore s = (Semaphore) f.get(gen);
        assertThat(s.availablePermits()).isEqualTo(5);
        assertThat(s.isFair()).isTrue();
    }

    @Test
    @DisplayName("Valid invoice XML → returns non-empty PDF bytes starting with %PDF")
    void generatePdf_validXml_returnsPdfBytes() throws Exception {
        FopInvoicePdfGenerator gen = new FopInvoicePdfGenerator(1, new SimpleMeterRegistry());
        String xml = "<invoice>"
                + "<invoiceNumber>INV-TEST-001</invoiceNumber>"
                + "<invoiceDate>2024-01-15</invoiceDate>"
                + "<seller><name>Test Seller</name><address>1 Test Rd</address>"
                + "<taxId>1234567890123</taxId></seller>"
                + "<buyer><name>Test Buyer</name><address>2 Test Rd</address>"
                + "<taxId>9876543210987</taxId></buyer>"
                + "<lineItems><item><description>Widget</description>"
                + "<quantity>1</quantity><unit>EA</unit>"
                + "<unitPrice>1000</unitPrice><amount>1000</amount></item></lineItems>"
                + "<subtotal>1000</subtotal><amountBeforeVat>1000</amountBeforeVat>"
                + "<vatRate>7</vatRate><vatAmount>70</vatAmount><grandTotal>1070</grandTotal>"
                + "</invoice>";

        byte[] result = gen.generatePdf(xml);

        assertThat(result).isNotEmpty();
        // All PDF files start with the %PDF header
        assertThat(new String(result, 0, 4, java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("%PDF");
    }

    @Test
    @DisplayName("Malformed XML → PdfGenerationException")
    void generatePdf_malformedXml_throwsPdfGenerationException() {
        FopInvoicePdfGenerator gen = new FopInvoicePdfGenerator(1, new SimpleMeterRegistry());
        assertThatThrownBy(() -> gen.generatePdf("this is not xml <<<"))
                .isInstanceOf(FopInvoicePdfGenerator.PdfGenerationException.class);
    }

    @Test
    @DisplayName("PdfGenerationException(String) 1-arg constructor carries the message")
    void pdfGenerationException_messageOnlyConstructor_hasMessage() {
        var ex = new FopInvoicePdfGenerator.PdfGenerationException("FOP failed");
        assertThat(ex.getMessage()).isEqualTo("FOP failed");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName("generatePdf() on an interrupted thread throws PdfGenerationException")
    void generatePdf_threadAlreadyInterrupted_throwsPdfGenerationException() {
        FopInvoicePdfGenerator gen = new FopInvoicePdfGenerator(1, new SimpleMeterRegistry());
        Thread.currentThread().interrupt();  // mark thread as interrupted before acquire()
        try {
            assertThatThrownBy(() -> gen.generatePdf("<invoice/>"))
                    .isInstanceOf(FopInvoicePdfGenerator.PdfGenerationException.class)
                    .hasMessageContaining("interrupted");
        } finally {
            Thread.interrupted();  // restore clean interrupted status for subsequent tests
        }
    }

    @Test
    @DisplayName("Semaphore blocks callers when all permits are held")
    void generatePdf_semaphoreBlocksWhenAtCapacity() throws Exception {
        FopInvoicePdfGenerator gen = new FopInvoicePdfGenerator(1, new SimpleMeterRegistry());

        // Drain the single permit so the next caller must wait
        Field f = FopInvoicePdfGenerator.class.getDeclaredField("renderSemaphore");
        f.setAccessible(true);
        Semaphore sem = (Semaphore) f.get(gen);
        sem.acquire();

        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = exec.submit(() -> {
                try {
                    gen.generatePdf("<invoice/>");
                } catch (FopInvoicePdfGenerator.PdfGenerationException ignored) {
                    // expected once permit is released — not what we are testing here
                }
            });

            // While the permit is held, the task must not complete
            assertThatThrownBy(() -> future.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            // Release permit → task unblocks and finishes (may fail on bad XML, that is fine)
            sem.release();
            future.get(5, TimeUnit.SECONDS);
        } finally {
            exec.shutdownNow();
        }
    }
}

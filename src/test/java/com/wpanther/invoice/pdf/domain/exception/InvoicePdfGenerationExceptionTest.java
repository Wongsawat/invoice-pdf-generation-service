package com.wpanther.invoice.pdf.domain.exception;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class InvoicePdfGenerationExceptionTest {

    @Test
    void constructor_withMessage_storesMessage() {
        var ex = new InvoicePdfGenerationException("test error");
        assertThat(ex.getMessage()).isEqualTo("test error");
    }

    @Test
    void constructor_withMessageAndCause_storesBoth() {
        var cause = new RuntimeException("root");
        var ex = new InvoicePdfGenerationException("wrapped", cause);
        assertThat(ex.getMessage()).isEqualTo("wrapped");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void isRuntimeException() {
        assertThat(new InvoicePdfGenerationException("x"))
                .isInstanceOf(RuntimeException.class);
    }
}

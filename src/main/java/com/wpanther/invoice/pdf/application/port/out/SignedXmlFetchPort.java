package com.wpanther.invoice.pdf.application.port.out;

/**
 * Output port for downloading a signed XML document from a remote URL.
 *
 * Keeps HTTP client details out of the application layer.
 * The implementation lives in {@code infrastructure/client/}.
 */
public interface SignedXmlFetchPort {

    /**
     * Download the signed XML content from the given URL.
     *
     * @param url URL pointing to the signed XML document (e.g. a MinIO pre-signed URL)
     * @return non-blank XML content
     * @throws SignedXmlFetchException if the HTTP request fails or the response is blank
     */
    String fetch(String url);

    class SignedXmlFetchException extends RuntimeException {
        public SignedXmlFetchException(String message) {
            super(message);
        }

        public SignedXmlFetchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

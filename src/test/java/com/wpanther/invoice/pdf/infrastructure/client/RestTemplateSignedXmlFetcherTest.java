package com.wpanther.invoice.pdf.infrastructure.client;

import com.wpanther.invoice.pdf.application.port.out.SignedXmlFetchPort.SignedXmlFetchException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RestTemplateSignedXmlFetcherTest {

    @Mock
    private RestTemplate restTemplate;

    @Test
    void fetch_allowedHost_succeeds() {
        var fetcher = new RestTemplateSignedXmlFetcher(restTemplate, "localhost");
        String url = "http://localhost:9000/invoices/signed.xml";
        when(restTemplate.getForObject(url, String.class)).thenReturn("<invoice/>");

        String result = fetcher.fetch(url);

        assertThat(result).isEqualTo("<invoice/>");
        verify(restTemplate).getForObject(url, String.class);
    }

    @Test
    void fetch_disallowedHost_throwsException() {
        var fetcher = new RestTemplateSignedXmlFetcher(restTemplate, "localhost");
        String url = "http://evil.example.com/steal-secrets";

        assertThatThrownBy(() -> fetcher.fetch(url))
                .isInstanceOf(SignedXmlFetchException.class)
                .hasMessageContaining("evil.example.com");

        verifyNoInteractions(restTemplate);
    }

    @Test
    void fetch_multipleAllowedHosts_allowsAllOfThem() {
        var fetcher = new RestTemplateSignedXmlFetcher(restTemplate, "minio-service, document-storage-service");
        String minioUrl = "http://minio-service:9000/bucket/doc.xml";
        String storageUrl = "http://document-storage-service:8084/docs/signed.xml";
        when(restTemplate.getForObject(minioUrl, String.class)).thenReturn("<invoice>1</invoice>");
        when(restTemplate.getForObject(storageUrl, String.class)).thenReturn("<invoice>2</invoice>");

        assertThat(fetcher.fetch(minioUrl)).isEqualTo("<invoice>1</invoice>");
        assertThat(fetcher.fetch(storageUrl)).isEqualTo("<invoice>2</invoice>");
    }

    @Test
    void fetch_nullContent_throwsException() {
        var fetcher = new RestTemplateSignedXmlFetcher(restTemplate, "localhost");
        String url = "http://localhost:9000/invoices/empty.xml";
        when(restTemplate.getForObject(url, String.class)).thenReturn(null);

        assertThatThrownBy(() -> fetcher.fetch(url))
                .isInstanceOf(SignedXmlFetchException.class)
                .hasMessageContaining("Empty response fetching signed XML from");
    }

    @Test
    void fetch_blankContent_throwsException() {
        var fetcher = new RestTemplateSignedXmlFetcher(restTemplate, "localhost");
        String url = "http://localhost:9000/invoices/blank.xml";
        when(restTemplate.getForObject(url, String.class)).thenReturn("   ");

        assertThatThrownBy(() -> fetcher.fetch(url))
                .isInstanceOf(SignedXmlFetchException.class)
                .hasMessageContaining("Empty response fetching signed XML from");
    }

    @Test
    void fetch_restTemplateThrows_wrapsException() {
        var fetcher = new RestTemplateSignedXmlFetcher(restTemplate, "localhost");
        String url = "http://localhost:9000/invoices/signed.xml";
        when(restTemplate.getForObject(url, String.class))
                .thenThrow(new RestClientException("connection refused"));

        assertThatThrownBy(() -> fetcher.fetch(url))
                .isInstanceOf(SignedXmlFetchException.class)
                .hasMessageContaining("Failed to download signed XML from")
                .hasCauseInstanceOf(RestClientException.class);
    }

    @Test
    void fetch_invalidUrl_throwsException() {
        var fetcher = new RestTemplateSignedXmlFetcher(restTemplate, "localhost");
        String url = "not a valid url ://???";

        assertThatThrownBy(() -> fetcher.fetch(url))
                .isInstanceOf(SignedXmlFetchException.class);

        verifyNoInteractions(restTemplate);
    }

    @Test
    void fetch_fileScheme_throwsException() {
        var fetcher = new RestTemplateSignedXmlFetcher(restTemplate, "localhost");
        String url = "file:///etc/passwd";

        assertThatThrownBy(() -> fetcher.fetch(url))
                .isInstanceOf(SignedXmlFetchException.class)
                .hasMessageContaining("disallowed scheme")
                .hasMessageContaining("file");

        verifyNoInteractions(restTemplate);
    }

    @Test
    void fetch_loopbackIpLiteralInAllowlist_stillRejected() {
        // Even when 127.0.0.1 is explicitly in the allowlist, it is rejected as a private IP
        var fetcher = new RestTemplateSignedXmlFetcher(restTemplate, "localhost,127.0.0.1");

        assertThatThrownBy(() -> fetcher.fetch("http://127.0.0.1/invoices/signed.xml"))
                .isInstanceOf(SignedXmlFetchException.class)
                .hasMessageContaining("private IP");
        verifyNoInteractions(restTemplate);
    }

    @Test
    void fetch_rfc1918IpLiteralInAllowlist_stillRejected() {
        var fetcher = new RestTemplateSignedXmlFetcher(restTemplate, "10.0.0.5");

        assertThatThrownBy(() -> fetcher.fetch("http://10.0.0.5/invoices/signed.xml"))
                .isInstanceOf(SignedXmlFetchException.class)
                .hasMessageContaining("private IP");
        verifyNoInteractions(restTemplate);
    }

    @Test
    void fetch_172_16_ipLiteralInAllowlist_stillRejected() {
        var fetcher = new RestTemplateSignedXmlFetcher(restTemplate, "172.16.0.1");

        assertThatThrownBy(() -> fetcher.fetch("http://172.16.0.1/invoices/signed.xml"))
                .isInstanceOf(SignedXmlFetchException.class)
                .hasMessageContaining("private IP");
        verifyNoInteractions(restTemplate);
    }

    @Test
    void fetch_localhostHostname_notBlockedByPrivateIpCheck() {
        // "localhost" is a hostname, not an IP literal — should pass the private IP check
        var fetcher = new RestTemplateSignedXmlFetcher(restTemplate, "localhost");
        String url = "http://localhost:9000/invoices/signed.xml";
        when(restTemplate.getForObject(url, String.class)).thenReturn("<invoice/>");

        assertThat(fetcher.fetch(url)).isEqualTo("<invoice/>");
    }

    @Test
    void fetch_4xxResponse_throwsExceptionWithVerifyMessage() {
        var fetcher = new RestTemplateSignedXmlFetcher(restTemplate, "localhost");
        String url = "http://localhost:9000/invoices/missing.xml";
        when(restTemplate.getForObject(url, String.class))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND, "Not Found", null, null, null));

        assertThatThrownBy(() -> fetcher.fetch(url))
                .isInstanceOf(SignedXmlFetchException.class)
                .hasMessageContaining("404")
                .hasMessageContaining("verify the URL is correct");
    }

    @Test
    void fetch_5xxResponse_throwsExceptionWithRetryMessage() {
        var fetcher = new RestTemplateSignedXmlFetcher(restTemplate, "localhost");
        String url = "http://localhost:9000/invoices/signed.xml";
        when(restTemplate.getForObject(url, String.class))
                .thenThrow(HttpServerErrorException.create(
                        HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", null, null, null));

        assertThatThrownBy(() -> fetcher.fetch(url))
                .isInstanceOf(SignedXmlFetchException.class)
                .hasMessageContaining("500")
                .hasMessageContaining("consider retrying");
    }

    @Test
    void fetch_networkTimeout_throwsExceptionWithNetworkMessage() {
        var fetcher = new RestTemplateSignedXmlFetcher(restTemplate, "localhost");
        String url = "http://localhost:9000/invoices/signed.xml";
        when(restTemplate.getForObject(url, String.class))
                .thenThrow(new ResourceAccessException("Connection timed out"));

        assertThatThrownBy(() -> fetcher.fetch(url))
                .isInstanceOf(SignedXmlFetchException.class)
                .hasMessageContaining("Network error")
                .hasMessageContaining("Connection timed out");
    }
}

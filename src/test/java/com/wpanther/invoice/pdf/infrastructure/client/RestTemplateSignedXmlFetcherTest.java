package com.wpanther.invoice.pdf.infrastructure.client;

import com.wpanther.invoice.pdf.application.port.out.SignedXmlFetchPort.SignedXmlFetchException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
                .hasMessageContaining("Failed to download signed XML from");
    }

    @Test
    void fetch_blankContent_throwsException() {
        var fetcher = new RestTemplateSignedXmlFetcher(restTemplate, "localhost");
        String url = "http://localhost:9000/invoices/blank.xml";
        when(restTemplate.getForObject(url, String.class)).thenReturn("   ");

        assertThatThrownBy(() -> fetcher.fetch(url))
                .isInstanceOf(SignedXmlFetchException.class)
                .hasMessageContaining("Failed to download signed XML from");
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
}

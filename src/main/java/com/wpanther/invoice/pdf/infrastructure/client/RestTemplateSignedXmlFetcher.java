package com.wpanther.invoice.pdf.infrastructure.client;

import com.wpanther.invoice.pdf.application.port.out.SignedXmlFetchPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * {@link SignedXmlFetchPort} adapter backed by a configured {@link RestTemplate}.
 *
 * Connect and read timeouts are controlled by {@code app.rest-client.*} properties
 * and wired into the {@link RestTemplate} bean in {@code OutboxConfig}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RestTemplateSignedXmlFetcher implements SignedXmlFetchPort {

    private final RestTemplate restTemplate;

    @Override
    public String fetch(String url) {
        log.debug("Fetching signed XML from: {}", url);
        try {
            String content = restTemplate.getForObject(url, String.class);
            if (content == null || content.isBlank()) {
                throw new SignedXmlFetchException("Failed to download signed XML from " + url);
            }
            return content;
        } catch (SignedXmlFetchException e) {
            throw e;
        } catch (Exception e) {
            throw new SignedXmlFetchException("Failed to download signed XML from " + url, e);
        }
    }
}

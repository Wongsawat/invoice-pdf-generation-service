package com.wpanther.invoice.pdf.infrastructure.client;

import com.wpanther.invoice.pdf.application.port.out.SignedXmlFetchPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link SignedXmlFetchPort} adapter backed by a configured {@link RestTemplate}.
 *
 * Connect and read timeouts are controlled by {@code app.rest-client.*} properties
 * and wired into the {@link RestTemplate} bean in {@code OutboxConfig}.
 *
 * Only hosts listed in {@code app.rest-client.allowed-hosts} (comma-separated) are
 * permitted. This prevents SSRF by rejecting URLs whose host is not in the allowlist.
 * Set the environment variable {@code REST_CLIENT_ALLOWED_HOSTS} to a comma-separated
 * list of trusted internal hostnames in production.
 */
@Component
@Slf4j
public class RestTemplateSignedXmlFetcher implements SignedXmlFetchPort {

    private final RestTemplate restTemplate;
    private final Set<String> allowedHosts;

    public RestTemplateSignedXmlFetcher(
            RestTemplate restTemplate,
            @Value("${app.rest-client.allowed-hosts:localhost}") String allowedHostsConfig) {
        this.restTemplate = restTemplate;
        this.allowedHosts = Arrays.stream(allowedHostsConfig.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public String fetch(String url) {
        validateUrl(url);
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

    private void validateUrl(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null || !allowedHosts.contains(host.toLowerCase())) {
                throw new SignedXmlFetchException(
                        "Rejected URL with disallowed host: " + host + ". Allowed hosts: " + allowedHosts);
            }
        } catch (SignedXmlFetchException e) {
            throw e;
        } catch (Exception e) {
            throw new SignedXmlFetchException("Invalid URL: " + url, e);
        }
    }
}

package com.wpanther.invoice.pdf.infrastructure.client;

import com.wpanther.invoice.pdf.application.port.out.SignedXmlFetchPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.InetAddress;
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
 * permitted, and only {@code http} / {@code https} schemes are accepted.
 * This prevents SSRF by rejecting URLs whose host is not in the allowlist and
 * rejecting non-HTTP schemes (e.g. {@code file://}, {@code ftp://}).
 * Set the environment variable {@code REST_CLIENT_ALLOWED_HOSTS} to a comma-separated
 * list of trusted internal hostnames in production.
 */
@Component
@Slf4j
public class RestTemplateSignedXmlFetcher implements SignedXmlFetchPort {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final RestTemplate restTemplate;
    private final Set<String> allowedHosts;

    public RestTemplateSignedXmlFetcher(
            RestTemplate restTemplate,
            @Value("${app.rest-client.allowed-hosts:localhost}") String allowedHostsConfig) {
        this.restTemplate = restTemplate;
        this.allowedHosts = Arrays.stream(allowedHostsConfig.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        if (this.allowedHosts.isEmpty()) {
            throw new IllegalStateException(
                    "app.rest-client.allowed-hosts is empty — at least one trusted host is required. "
                    + "Set the REST_CLIENT_ALLOWED_HOSTS environment variable.");
        }
    }

    @Override
    public String fetch(String url) {
        validateUrl(url);
        log.debug("Fetching signed XML from: {}", url);
        try {
            String content = restTemplate.getForObject(url, String.class);
            if (content == null || content.isBlank()) {
                throw new SignedXmlFetchException("Empty response fetching signed XML from " + url);
            }
            return content;
        } catch (SignedXmlFetchException e) {
            throw e;
        } catch (HttpClientErrorException e) {
            // 4xx — bad request or not found; retrying will not help
            throw new SignedXmlFetchException(
                    "HTTP " + e.getStatusCode().value() + " fetching signed XML from " + url
                    + " — verify the URL is correct and the document exists", e);
        } catch (HttpServerErrorException e) {
            // 5xx — upstream server error; may be transient
            throw new SignedXmlFetchException(
                    "HTTP " + e.getStatusCode().value() + " fetching signed XML from " + url
                    + " — upstream server error, consider retrying", e);
        } catch (ResourceAccessException e) {
            // network-level failure (timeout, DNS, connection refused)
            throw new SignedXmlFetchException(
                    "Network error fetching signed XML from " + url + ": " + e.getMessage(), e);
        } catch (Exception e) {
            throw new SignedXmlFetchException("Failed to download signed XML from " + url, e);
        }
    }

    /**
     * Returns {@code true} when {@code host} is an IPv4/IPv6 literal in a private or loopback
     * range: 127.x, 10.x, 172.16–31.x, 192.168.x, 169.254.x (link-local), or {@code ::1}.
     * Hostnames (e.g. {@code localhost}) always return {@code false}.
     */
    private static boolean isPrivateIpLiteral(String host) {
        if (host.startsWith("127.") || host.equals("::1")) return true;
        if (host.startsWith("169.254."))                   return true;
        if (host.startsWith("10."))                        return true;
        if (host.startsWith("192.168."))                   return true;
        if (host.startsWith("172.")) {
            String[] parts = host.split("\\.", -1);
            if (parts.length >= 2) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    return second >= 16 && second <= 31;
                } catch (NumberFormatException ignored) { }
            }
        }
        return false;
    }

    private void validateUrl(String url) {
        try {
            URI uri = URI.create(url);

            // Reject non-HTTP(S) schemes to prevent file://, ftp://, etc.
            String scheme = uri.getScheme();
            if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
                throw new SignedXmlFetchException(
                        "Rejected URL with disallowed scheme '" + scheme + "'. Allowed: " + ALLOWED_SCHEMES);
            }

            // Reject hosts not in the allowlist (covers hostname AND IP literals)
            String host = uri.getHost();
            if (host == null || !allowedHosts.contains(host.toLowerCase())) {
                throw new SignedXmlFetchException(
                        "Rejected URL with disallowed host: " + host + ". Allowed hosts: " + allowedHosts);
            }

            // Defense-in-depth: reject private IP literals even if explicitly listed in the
            // allowlist. Private IPs should never appear in a production allowlist, and allowing
            // them enables SSRF via misconfiguration. Use hostnames instead.
            if (isPrivateIpLiteral(host)) {
                throw new SignedXmlFetchException(
                        "Rejected URL with private IP literal: " + host + ". Use a hostname instead.");
            }

            // DNS rebinding protection: resolve the hostname and reject if ANY returned IP
            // is in a private range. An attacker who controls DNS for an allowlisted external
            // name could re-point it to internal infrastructure after the allowlist check.
            // 'localhost' is excluded — it always maps to loopback in any JVM and the
            // rebinding attack targets external names, not loopback aliases.
            // getAllByName is used instead of getByName to cover multi-homed hosts: a hostname
            // with both a public and a private A-record is rejected regardless of which IP the
            // JVM would select for the actual connection.
            // NOTE: InetAddress.getAllByName() is a blocking OS call. Under degraded DNS
            // conditions it can stall the calling thread for up to the OS resolver timeout
            // (typically 5–30 s). If DNS latency becomes a concern, move this check to a
            // bounded executor or adopt an async DNS resolver.
            // If DNS resolution fails entirely (e.g. in unit-test environments without network),
            // a warning is logged and the request proceeds; the connection will fail at the
            // network layer with a clear error.
            if (!host.equalsIgnoreCase("localhost")) {
                try {
                    for (InetAddress addr : InetAddress.getAllByName(host)) {
                        String resolvedIp = addr.getHostAddress();
                        if (isPrivateIpLiteral(resolvedIp)) {
                            throw new SignedXmlFetchException(
                                    "Rejected URL: host '" + host + "' resolves to private IP '"
                                    + resolvedIp + "' (DNS rebinding protection). "
                                    + "Use an external, publicly-routable hostname.");
                        }
                    }
                } catch (SignedXmlFetchException e) {
                    throw e;
                } catch (Exception e) {
                    log.warn("DNS rebinding check: could not resolve host '{}': {} — proceeding with fetch",
                            host, e.getMessage());
                }
            }
        } catch (SignedXmlFetchException e) {
            throw e;
        } catch (Exception e) {
            throw new SignedXmlFetchException("Invalid URL: " + url, e);
        }
    }
}

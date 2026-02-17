package com.wpanther.invoice.pdf.application.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * Service for fetching signed XML content from MinIO/S3 storage.
 * Uses RestTemplate to fetch from the storage URL provided in XmlStoredEvent.
 */
@Service
@Slf4j
public class StorageFetchService {

    private final RestTemplate restTemplate;
    private final boolean enableStorageFetch;

    public StorageFetchService(
            @Value("${app.storage.fetch.enabled:true}") boolean enableStorageFetch) {
        this.enableStorageFetch = enableStorageFetch;
        this.restTemplate = new RestTemplate();
        log.info("StorageFetchService initialized with fetch enabled: {}", enableStorageFetch);
    }

    /**
     * Fetch signed XML content from MinIO/S3 using the storage URL.
     *
     * @param storageUrl The URL to fetch the XML from
     * @return The signed XML content as a String
     * @throws RuntimeException if fetching fails
     */
    public String fetchXmlFromStorage(String storageUrl) {
        if (!enableStorageFetch) {
            throw new IllegalStateException("Storage fetch is disabled. Enable with app.storage.fetch.enabled=true");
        }

        try {
            log.debug("Fetching XML from storage: {}", storageUrl);

            String xmlContent = restTemplate.getForObject(storageUrl, String.class);

            if (xmlContent == null || xmlContent.isEmpty()) {
                throw new IllegalStateException("Empty XML content received from: " + storageUrl);
            }

            // Validate it's XML
            if (!xmlContent.trim().startsWith("<?xml") && !xmlContent.trim().startsWith("<")) {
                log.warn("Fetched content may not be valid XML, starting with: {}",
                        xmlContent.substring(0, Math.min(50, xmlContent.length())));
            }

            log.debug("Successfully fetched {} bytes from storage", xmlContent.length());
            return xmlContent;

        } catch (Exception e) {
            log.error("Failed to fetch XML from storage: {}", storageUrl, e);
            throw new RuntimeException("Failed to fetch signed XML from storage: " + storageUrl, e);
        }
    }
}

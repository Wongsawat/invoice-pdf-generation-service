package com.wpanther.invoice.pdf.infrastructure.adapter.out.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Shared utilities for messaging infrastructure classes.
 */
@Slf4j
class MessagingUtils {

    private MessagingUtils() {}

    /**
     * Serialize a header map to JSON. Returns {@code null} on failure so the caller
     * can still proceed (missing headers are non-fatal for outbox routing).
     */
    static String toJson(Map<String, String> map, ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize messaging headers to JSON", e);
            return null;
        }
    }
}

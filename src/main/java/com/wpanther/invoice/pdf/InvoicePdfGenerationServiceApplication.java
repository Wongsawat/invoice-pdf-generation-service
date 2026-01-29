package com.wpanther.invoice.pdf;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Spring Boot application for Invoice PDF Generation Service
 *
 * This service generates PDF/A-3 documents for Thai e-Tax invoices
 * with embedded signed XML content.
 */
@SpringBootApplication
@EnableDiscoveryClient
public class InvoicePdfGenerationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InvoicePdfGenerationServiceApplication.class, args);
    }
}

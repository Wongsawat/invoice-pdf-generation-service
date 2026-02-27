package com.wpanther.invoice.pdf.infrastructure.config;

import com.wpanther.invoice.pdf.infrastructure.persistence.outbox.JpaOutboxEventRepository;
import com.wpanther.invoice.pdf.infrastructure.persistence.outbox.SpringDataOutboxRepository;
import com.wpanther.saga.domain.outbox.OutboxEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class OutboxConfig {

    @Value("${app.rest-client.connect-timeout:5000}")
    private int connectTimeout;

    @Value("${app.rest-client.read-timeout:10000}")
    private int readTimeout;

    @Bean
    @ConditionalOnMissingBean(OutboxEventRepository.class)
    public OutboxEventRepository outboxEventRepository(SpringDataOutboxRepository springRepository) {
        return new JpaOutboxEventRepository(springRepository);
    }

    @Bean
    public RestTemplate restTemplate() {
        if (connectTimeout <= 0) {
            throw new IllegalStateException(
                    "app.rest-client.connect-timeout must be > 0, got: " + connectTimeout);
        }
        if (readTimeout <= 0) {
            throw new IllegalStateException(
                    "app.rest-client.read-timeout must be > 0, got: " + readTimeout);
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return new RestTemplate(factory);
    }
}

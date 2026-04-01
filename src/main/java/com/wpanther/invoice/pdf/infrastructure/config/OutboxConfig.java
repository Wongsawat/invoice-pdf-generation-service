package com.wpanther.invoice.pdf.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.invoice.pdf.infrastructure.adapter.out.persistence.outbox.JpaOutboxEventRepository;
import com.wpanther.invoice.pdf.infrastructure.adapter.out.persistence.outbox.SpringDataOutboxRepository;
import com.wpanther.saga.domain.outbox.OutboxEventRepository;
import com.wpanther.saga.infrastructure.outbox.OutboxService;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
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
    @ConditionalOnMissingBean(OutboxService.class)
    public OutboxService outboxService(OutboxEventRepository repository, ObjectMapper objectMapper) {
        return new OutboxService(repository, objectMapper);
    }

    /**
     * Exposes the HC5 client as a managed bean so Spring calls {@code close()} on it
     * when the application context shuts down (Spring recognises {@link java.io.Closeable}
     * beans and invokes {@code close()} via {@code DisposableBeanAdapter}).
     *
     * HC5 timeout semantics:
     *   setConnectTimeout            → TCP connection establishment
     *   setConnectionRequestTimeout  → pool-borrow timeout (use connectTimeout, not readTimeout)
     *   setResponseTimeout           → socket read / server response timeout (the "read timeout")
     */
    @Bean
    public CloseableHttpClient httpClient() {
        if (connectTimeout <= 0) {
            throw new IllegalStateException(
                    "app.rest-client.connect-timeout must be > 0, got: " + connectTimeout);
        }
        if (readTimeout <= 0) {
            throw new IllegalStateException(
                    "app.rest-client.read-timeout must be > 0, got: " + readTimeout);
        }
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeout))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectTimeout))
                .setResponseTimeout(Timeout.ofMilliseconds(readTimeout))
                .build();
        return HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .build();
    }

    @Bean
    public RestTemplate restTemplate(CloseableHttpClient httpClient) {
        return new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
    }
}

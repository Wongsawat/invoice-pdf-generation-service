package com.wpanther.invoice.pdf.infrastructure.persistence.outbox;

import com.wpanther.saga.domain.outbox.OutboxEvent;
import com.wpanther.saga.domain.outbox.OutboxStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxEventEntityTest {

    private static final UUID ID = UUID.randomUUID();
    private static final Instant NOW = Instant.now();

    private OutboxEvent buildEvent() {
        return OutboxEvent.builder()
                .id(ID)
                .aggregateType("InvoicePdfDocument")
                .aggregateId("inv-001")
                .eventType("pdf.generated.invoice")
                .payload("{\"key\":\"value\"}")
                .createdAt(NOW)
                .publishedAt(null)
                .status(OutboxStatus.PENDING)
                .retryCount(2)
                .errorMessage("some error")
                .topic("pdf.generated.invoice")
                .partitionKey("inv-001")
                .headers("{\"h\":\"v\"}")
                .build();
    }

    @Test
    void fromDomain_mapsAllFieldsCorrectly() {
        OutboxEvent event = buildEvent();

        OutboxEventEntity entity = OutboxEventEntity.fromDomain(event);

        assertThat(entity.getId()).isEqualTo(ID);
        assertThat(entity.getAggregateType()).isEqualTo("InvoicePdfDocument");
        assertThat(entity.getAggregateId()).isEqualTo("inv-001");
        assertThat(entity.getEventType()).isEqualTo("pdf.generated.invoice");
        assertThat(entity.getPayload()).isEqualTo("{\"key\":\"value\"}");
        assertThat(entity.getCreatedAt()).isEqualTo(NOW);
        assertThat(entity.getPublishedAt()).isNull();
        assertThat(entity.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(entity.getRetryCount()).isEqualTo(2);
        assertThat(entity.getErrorMessage()).isEqualTo("some error");
        assertThat(entity.getTopic()).isEqualTo("pdf.generated.invoice");
        assertThat(entity.getPartitionKey()).isEqualTo("inv-001");
        assertThat(entity.getHeaders()).isEqualTo("{\"h\":\"v\"}");
    }

    @Test
    void toDomain_mapsAllFieldsCorrectly() {
        OutboxEventEntity entity = OutboxEventEntity.builder()
                .id(ID)
                .aggregateType("InvoicePdfDocument")
                .aggregateId("inv-001")
                .eventType("pdf.generated.invoice")
                .payload("{\"key\":\"value\"}")
                .createdAt(NOW)
                .publishedAt(null)
                .status(OutboxStatus.PENDING)
                .retryCount(2)
                .errorMessage("some error")
                .topic("pdf.generated.invoice")
                .partitionKey("inv-001")
                .headers("{\"h\":\"v\"}")
                .build();

        OutboxEvent domain = entity.toDomain();

        assertThat(domain.getId()).isEqualTo(ID);
        assertThat(domain.getAggregateType()).isEqualTo("InvoicePdfDocument");
        assertThat(domain.getAggregateId()).isEqualTo("inv-001");
        assertThat(domain.getEventType()).isEqualTo("pdf.generated.invoice");
        assertThat(domain.getPayload()).isEqualTo("{\"key\":\"value\"}");
        assertThat(domain.getCreatedAt()).isEqualTo(NOW);
        assertThat(domain.getPublishedAt()).isNull();
        assertThat(domain.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(domain.getRetryCount()).isEqualTo(2);
        assertThat(domain.getErrorMessage()).isEqualTo("some error");
        assertThat(domain.getTopic()).isEqualTo("pdf.generated.invoice");
        assertThat(domain.getPartitionKey()).isEqualTo("inv-001");
        assertThat(domain.getHeaders()).isEqualTo("{\"h\":\"v\"}");
    }

    @Test
    void fromDomain_thenToDomain_roundtrip_preservesAllFields() {
        OutboxEvent original = buildEvent();

        OutboxEvent roundtripped = OutboxEventEntity.fromDomain(original).toDomain();

        assertThat(roundtripped.getId()).isEqualTo(original.getId());
        assertThat(roundtripped.getAggregateType()).isEqualTo(original.getAggregateType());
        assertThat(roundtripped.getPayload()).isEqualTo(original.getPayload());
        assertThat(roundtripped.getStatus()).isEqualTo(original.getStatus());
        assertThat(roundtripped.getRetryCount()).isEqualTo(original.getRetryCount());
        assertThat(roundtripped.getTopic()).isEqualTo(original.getTopic());
    }

    @Test
    void onCreate_setsDefaultsWhenFieldsAreNull() {
        OutboxEventEntity entity = new OutboxEventEntity();

        entity.onCreate();

        assertThat(entity.getId()).isNotNull();
        assertThat(entity.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(entity.getCreatedAt()).isNotNull();
        assertThat(entity.getRetryCount()).isEqualTo(0);
    }

    @Test
    void onCreate_doesNotOverrideExistingValues() {
        UUID existingId = UUID.randomUUID();
        Instant existingCreatedAt = Instant.ofEpochSecond(1_000_000);

        OutboxEventEntity entity = new OutboxEventEntity();
        entity.setId(existingId);
        entity.setStatus(OutboxStatus.FAILED);
        entity.setCreatedAt(existingCreatedAt);
        entity.setRetryCount(3);

        entity.onCreate();

        assertThat(entity.getId()).isEqualTo(existingId);
        assertThat(entity.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(entity.getCreatedAt()).isEqualTo(existingCreatedAt);
        assertThat(entity.getRetryCount()).isEqualTo(3);
    }
}

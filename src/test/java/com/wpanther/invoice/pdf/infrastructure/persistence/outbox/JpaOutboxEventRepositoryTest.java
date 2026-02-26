package com.wpanther.invoice.pdf.infrastructure.persistence.outbox;

import com.wpanther.saga.domain.outbox.OutboxEvent;
import com.wpanther.saga.domain.outbox.OutboxStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JpaOutboxEventRepositoryTest {

    @Mock
    private SpringDataOutboxRepository springRepository;

    private JpaOutboxEventRepository repository;

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.now();

    @BeforeEach
    void setUp() {
        repository = new JpaOutboxEventRepository(springRepository);
    }

    private OutboxEvent buildEvent() {
        return OutboxEvent.builder()
                .id(EVENT_ID)
                .aggregateType("InvoicePdfDocument")
                .aggregateId("inv-001")
                .eventType("pdf.generated.invoice")
                .payload("{\"invoiceId\":\"inv-001\"}")
                .createdAt(NOW)
                .publishedAt(null)
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .errorMessage(null)
                .topic("pdf.generated.invoice")
                .partitionKey("inv-001")
                .headers(null)
                .build();
    }

    private OutboxEventEntity buildEntity() {
        return OutboxEventEntity.fromDomain(buildEvent());
    }

    @Test
    void save_delegatesToSpringRepositoryAndReturnsDomain() {
        OutboxEventEntity savedEntity = buildEntity();
        when(springRepository.save(any(OutboxEventEntity.class))).thenReturn(savedEntity);

        OutboxEvent result = repository.save(buildEvent());

        verify(springRepository).save(any(OutboxEventEntity.class));
        assertThat(result.getId()).isEqualTo(EVENT_ID);
        assertThat(result.getAggregateType()).isEqualTo("InvoicePdfDocument");
        assertThat(result.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    void findById_delegatesToSpringRepositoryAndMapsToDomain() {
        OutboxEventEntity entity = buildEntity();
        when(springRepository.findById(EVENT_ID)).thenReturn(Optional.of(entity));

        Optional<OutboxEvent> result = repository.findById(EVENT_ID);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(EVENT_ID);
        verify(springRepository).findById(EVENT_ID);
    }

    @Test
    void findById_returnsEmptyWhenNotFound() {
        when(springRepository.findById(EVENT_ID)).thenReturn(Optional.empty());

        Optional<OutboxEvent> result = repository.findById(EVENT_ID);

        assertThat(result).isEmpty();
    }

    @Test
    void findPendingEvents_delegatesWithPendingStatusAndPageable() {
        OutboxEventEntity entity = buildEntity();
        when(springRepository.findByStatusOrderByCreatedAtAsc(
                eq(OutboxStatus.PENDING), any(Pageable.class)))
                .thenReturn(List.of(entity));

        List<OutboxEvent> result = repository.findPendingEvents(10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(EVENT_ID);
        verify(springRepository).findByStatusOrderByCreatedAtAsc(
                eq(OutboxStatus.PENDING), any(Pageable.class));
    }

    @Test
    void findFailedEvents_delegatesWithPageable() {
        OutboxEventEntity entity = buildEntity();
        when(springRepository.findFailedEventsOrderByCreatedAtAsc(any(Pageable.class)))
                .thenReturn(List.of(entity));

        List<OutboxEvent> result = repository.findFailedEvents(5);

        assertThat(result).hasSize(1);
        verify(springRepository).findFailedEventsOrderByCreatedAtAsc(any(Pageable.class));
    }

    @Test
    void deletePublishedBefore_delegatesAndReturnsCount() {
        Instant cutoff = Instant.now();
        when(springRepository.deletePublishedBefore(cutoff)).thenReturn(7);

        int deleted = repository.deletePublishedBefore(cutoff);

        assertThat(deleted).isEqualTo(7);
        verify(springRepository).deletePublishedBefore(cutoff);
    }

    @Test
    void findByAggregate_delegatesWithTypeAndIdAndReturnsMappedList() {
        OutboxEventEntity entity = buildEntity();
        when(springRepository.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(
                "InvoicePdfDocument", "inv-001"))
                .thenReturn(List.of(entity));

        List<OutboxEvent> result = repository.findByAggregate("InvoicePdfDocument", "inv-001");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAggregateId()).isEqualTo("inv-001");
        verify(springRepository).findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(
                "InvoicePdfDocument", "inv-001");
    }
}

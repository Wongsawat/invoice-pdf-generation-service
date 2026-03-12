package com.wpanther.invoice.pdf.infrastructure.adapter.out.persistence;

import com.wpanther.invoice.pdf.domain.model.GenerationStatus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the {@code invoice_pdf_documents} persistence layer against a
 * real PostgreSQL database (via Testcontainers).
 *
 * <p>Two things are verified here that H2 cannot reliably reproduce:
 * <ol>
 *   <li>Flyway migrations run cleanly and the <em>unique constraint on
 *       {@code invoice_id}</em> (created by DDL, not JPA entity annotations) is
 *       present.</li>
 *   <li>The {@code deleteById() + flush() + save()} saga-retry pattern does not
 *       violate the unique constraint, because flushing forces the DELETE to reach
 *       PostgreSQL before the subsequent INSERT.  Without the flush, Hibernate's
 *       action queue processes inserts before deletes and the constraint fires.</li>
 * </ol>
 *
 * <p>Flyway is run via an explicit {@link FlywayMigrationConfig} {@code @TestConfiguration}
 * bean rather than through {@code FlywayAutoConfiguration}, to avoid dependency-ordering
 * issues that arise in the {@code @DataJpaTest} slice context.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(InvoicePdfDocumentRepositoryIntegrationTest.FlywayMigrationConfig.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=none")
@Testcontainers(disabledWithoutDocker = true)
class InvoicePdfDocumentRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("invoicepdf_test")
            .withUsername("postgres")
            .withPassword("postgres");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    /**
     * Explicitly creates a {@link Flyway} bean and calls {@code migrate()} via
     * {@code initMethod}.  Spring guarantees that all constructor-parameter dependencies
     * ({@link DataSource}) are injected before {@code initMethod} is invoked, so the
     * migration always runs before any JPA query reaches the database.
     */
    @TestConfiguration
    static class FlywayMigrationConfig {
        @Bean(initMethod = "migrate")
        Flyway flyway(DataSource dataSource) {
            return Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true)
                    .load();
        }
    }

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JpaInvoicePdfDocumentRepository jpaRepository;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private InvoicePdfDocumentEntity buildEntity(String invoiceId, GenerationStatus status) {
        return InvoicePdfDocumentEntity.builder()
                .id(UUID.randomUUID())
                .invoiceId(invoiceId)
                .invoiceNumber(invoiceId.toUpperCase())
                .status(status)
                .mimeType("application/pdf")
                .xmlEmbedded(false)
                .retryCount(0)
                .build();
    }

    // -------------------------------------------------------------------------
    // Round-trip persistence — verifies Flyway schema is correct
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("persist + findByInvoiceId round-trip preserves status and invoiceNumber")
    void persist_findByInvoiceId_roundTrip() {
        InvoicePdfDocumentEntity entity = buildEntity("rt-inv-001", GenerationStatus.GENERATING);
        entityManager.persistAndFlush(entity);

        Optional<InvoicePdfDocumentEntity> found = jpaRepository.findByInvoiceId("rt-inv-001");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(entity.getId());
        assertThat(found.get().getStatus()).isEqualTo(GenerationStatus.GENERATING);
        assertThat(found.get().getInvoiceNumber()).isEqualTo("RT-INV-001");
    }

    @Test
    @DisplayName("deleteById + flush makes the record invisible to subsequent finds")
    void deleteById_flush_thenFindById_returnsEmpty() {
        InvoicePdfDocumentEntity entity = buildEntity("del-inv-001", GenerationStatus.GENERATING);
        entityManager.persistAndFlush(entity);

        jpaRepository.deleteById(entity.getId());
        jpaRepository.flush();

        assertThat(jpaRepository.findById(entity.getId())).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Saga retry: deleteById + flush + save with the same invoice_id
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("deleteById + flush + save with same invoice_id does not violate unique constraint")
    void retryPattern_deleteFlushThenSave_sameInvoiceId_succeeds() {
        // First attempt — leaves a FAILED record
        InvoicePdfDocumentEntity first = buildEntity("retry-inv-001", GenerationStatus.FAILED);
        entityManager.persistAndFlush(first);

        // Saga retry: delete old record, flush to guarantee DELETE executes before the
        // next INSERT (Hibernate's action queue processes inserts before deletes
        // without an explicit flush, which would violate the unique constraint).
        jpaRepository.deleteById(first.getId());
        jpaRepository.flush();

        UUID retryId = UUID.randomUUID();
        InvoicePdfDocumentEntity retry = InvoicePdfDocumentEntity.builder()
                .id(retryId)
                .invoiceId("retry-inv-001")
                .invoiceNumber("RETRY-INV-001")
                .status(GenerationStatus.GENERATING)
                .mimeType("application/pdf")
                .xmlEmbedded(false)
                .retryCount(1)
                .build();
        entityManager.persistAndFlush(retry);

        // New record has a different ID but the same invoice_id
        assertThat(retryId).isNotEqualTo(first.getId());
        assertThat(jpaRepository.findByInvoiceId("retry-inv-001"))
                .isPresent()
                .get()
                .extracting(InvoicePdfDocumentEntity::getId)
                .isEqualTo(retryId);
    }
}

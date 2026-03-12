# Hexagonal Architecture Migration Design
**Service**: invoice-pdf-generation-service
**Date**: 2026-03-09
**Approach**: Full Canonical Hexagonal Architecture (DDD + Port/Adapter Pattern)

---

## Goals

- Enforce the dependency rule end-to-end: `domain` ← `application` ← `infrastructure`
- Add missing inbound ports (`application/usecase/`)
- Keep domain-owned repository port in `domain/repository/`
- Move all Kafka wire DTOs out of `domain/event/` into adapter packages
- Restructure `infrastructure/` into `adapter/in/` and `adapter/out/` sub-packages
- Rename `application/port/in/` to `application/usecase/` for clarity

---

## Layer Responsibilities

| Layer | Purpose | Contents |
|-------|---------|----------|
| `domain/` | Core business rules & models — zero framework imports | `model/`, `event/`, `exception/`, `repository/` (domain-owned outbound port), `service/` |
| `application/` | Orchestration & use cases — imports domain only | `usecase/` (inbound ports), `port/out/` (non-domain outbound ports), `service/` |
| `infrastructure/` | All outside-world interactions | `config/`, `adapter/in/`, `adapter/out/` |

---

## Target Package Structure

```
com.wpanther.invoice.pdf/
│
├── domain/
│   ├── model/
│   │   ├── InvoicePdfDocument.java            (unchanged)
│   │   └── GenerationStatus.java              (unchanged)
│   ├── event/                                 (reserved for true domain events — currently empty)
│   ├── exception/
│   │   └── InvoicePdfGenerationException.java (NEW)
│   ├── repository/
│   │   └── InvoicePdfDocumentRepository.java  (STAYS — domain-owned outbound port)
│   └── service/
│       └── InvoicePdfGenerationService.java   (unchanged — domain service interface)
│
├── application/
│   ├── usecase/                               (NEW — inbound ports / driving side)
│   │   ├── ProcessInvoicePdfUseCase.java
│   │   └── CompensateInvoicePdfUseCase.java
│   ├── port/out/                              (non-domain outbound ports)
│   │   ├── PdfStoragePort.java                (unchanged)
│   │   ├── SagaReplyPort.java                 (unchanged)
│   │   ├── PdfEventPort.java                  (unchanged)
│   │   └── SignedXmlFetchPort.java            (unchanged)
│   └── service/
│       ├── SagaCommandHandler.java            (implements ProcessInvoicePdfUseCase
│       │                                       + CompensateInvoicePdfUseCase)
│       └── InvoicePdfDocumentService.java     (unchanged logic)
│
└── infrastructure/
    ├── config/
    │   ├── MinioConfig.java                   (STAYS — bean factory)
    │   └── OutboxConfig.java                  (STAYS — bean factory)
    └── adapter/
        ├── in/
        │   └── kafka/
        │       ├── SagaRouteConfig.java               (MOVED from infrastructure/config/)
        │       ├── KafkaProcessInvoicePdfCommand.java  (MOVED from domain/event/, renamed)
        │       ├── KafkaCompensateInvoicePdfCommand.java (MOVED from domain/event/, renamed)
        │       └── KafkaCommandMapper.java             (NEW — wire DTO → usecase input)
        └── out/
            ├── persistence/
            │   ├── InvoicePdfDocumentEntity.java
            │   │   (MOVED from infrastructure/persistence/)
            │   ├── InvoicePdfDocumentRepositoryAdapter.java
            │   │   (MOVED; implements domain/repository/InvoicePdfDocumentRepository)
            │   ├── JpaInvoicePdfDocumentRepository.java
            │   │   (MOVED)
            │   └── outbox/
            │       ├── OutboxEventEntity.java
            │       ├── JpaOutboxEventRepository.java
            │       └── SpringDataOutboxRepository.java
            ├── messaging/
            │   ├── EventPublisher.java           (MOVED; implements PdfEventPort)
            │   ├── SagaReplyPublisher.java       (MOVED; implements SagaReplyPort)
            │   ├── MessagingUtils.java           (MOVED)
            │   ├── InvoicePdfReplyEvent.java     (MOVED from domain/event/ — Kafka wire DTO)
            │   └── InvoicePdfGeneratedEvent.java (MOVED from domain/event/ — Kafka wire DTO)
            ├── storage/
            │   └── MinioStorageAdapter.java      (MOVED; implements PdfStoragePort)
            ├── client/
            │   └── RestTemplateSignedXmlFetcher.java
            │       (MOVED; implements SignedXmlFetchPort)
            └── pdf/
                ├── FopInvoicePdfGenerator.java       (MOVED from infrastructure/pdf/)
                ├── PdfA3Converter.java               (MOVED)
                └── InvoicePdfGenerationServiceImpl.java
                    (MOVED; implements InvoicePdfGenerationService)
```

---

## Dependency Rule — Import Graph

```
infrastructure/adapter/in/kafka      → application/usecase
infrastructure/adapter/out/persistence → domain/repository, domain/model
infrastructure/adapter/out/messaging   → application/port/out
infrastructure/adapter/out/storage     → application/port/out
infrastructure/adapter/out/client      → application/port/out
infrastructure/adapter/out/pdf         → domain/service

application/service  → application/usecase (implements)
                     → application/port/out (injected)
                     → domain/repository    (injected)
                     → domain/model
                     → domain/service       (delegates to)

domain/*  → (nothing outside domain)
```

No upward arrows. No `infrastructure` or `application` import inside `domain`.

---

## New Components

### `application/usecase/ProcessInvoicePdfUseCase.java`
```java
public interface ProcessInvoicePdfUseCase {
    void handle(KafkaProcessInvoicePdfCommand command);
}
```

### `application/usecase/CompensateInvoicePdfUseCase.java`
```java
public interface CompensateInvoicePdfUseCase {
    void handle(KafkaCompensateInvoicePdfCommand command);
}
```

`SagaCommandHandler` implements both interfaces. `SagaRouteConfig` injects the interfaces — never the concrete class.

### `infrastructure/adapter/in/kafka/KafkaCommandMapper.java`
Maps Jackson-annotated wire DTOs to plain use-case input objects.
Single static/instance method per command type.

### `domain/exception/InvoicePdfGenerationException.java`
Runtime exception thrown by aggregate root and domain service on invariant violations.
Replaces ad-hoc `IllegalStateException` throws in `InvoicePdfDocument`.

---

## Data Flow

### Command Processing (Happy Path)

```
Kafka → SagaRouteConfig
    deserialise → KafkaProcessInvoicePdfCommand
    KafkaCommandMapper.toUseCase()
    ProcessInvoicePdfUseCase.handle(command)          [SagaCommandHandler]
        [TX 1 ~10ms]  beginGeneration()
            InvoicePdfDocumentRepository.findByInvoiceId()  → idempotency check
            InvoicePdfDocument.startGeneration()            → PENDING→GENERATING
            InvoicePdfDocumentRepository.save()
        [NO TX ~1-3s]
            SignedXmlFetchPort.fetch(signedXmlUrl)
            InvoicePdfGenerationService.generatePdf(...)
                convertJsonToXml()
                FopInvoicePdfGenerator.generatePdf()         → Semaphore-guarded
                PdfA3Converter.convertToPdfA3()
            PdfStoragePort.store(bytes)
        [TX 2 ~100ms]  completeGenerationAndPublish()
            InvoicePdfDocument.markCompleted(url, size)     → GENERATING→COMPLETED
            InvoicePdfDocumentRepository.save()
            SagaReplyPort.publishSuccess(...)               → outbox row
            PdfEventPort.publishPdfGenerated(...)           → outbox row
```

### Compensation Flow

```
Kafka → SagaRouteConfig
    → KafkaCompensateInvoicePdfCommand
    CompensateInvoicePdfUseCase.handle()              [SagaCommandHandler]
        [TX] deleteAndPublishCompensated()
            InvoicePdfDocumentRepository.deleteById() + flush
            SagaReplyPort.publishCompensated(...)     → outbox row
        [best-effort, no TX]
            PdfStoragePort.delete(s3Key)              → MinioStorageAdapter (CB)
```

---

## Error Handling

| Failure point | Behaviour |
|---|---|
| Deserialization error | Dead Letter Channel → `pdf.generation.invoice.dlq` after 3 Camel retries |
| `SignedXmlFetchPort` throws | GENERATING→FAILED + `SagaReplyPort.publishFailure()` outbox row |
| FOP/PDFBox throws | Same — FAILED + FAILURE reply |
| `PdfStoragePort` throws (CB open) | Same path |
| TX 2 fails | Camel retry → idempotency check (COMPLETED case re-publishes, no regeneration) |
| Max retries exceeded | `publishOrchestrationFailure()` in `REQUIRES_NEW` TX before DLQ |
| Domain invariant violated | `InvoicePdfGenerationException` thrown from aggregate, bubbles to `SagaCommandHandler` |

---

## Testing Strategy

### Test Package Structure

```
test/java/com/wpanther/invoice/pdf/
├── domain/
│   ├── model/InvoicePdfDocumentTest.java                    (unchanged)
│   └── exception/InvoicePdfGenerationExceptionTest.java     (NEW)
├── application/
│   └── service/
│       ├── SagaCommandHandlerTest.java                      (update imports)
│       └── InvoicePdfDocumentServiceTest.java               (update imports)
└── infrastructure/
    ├── adapter/
    │   ├── in/kafka/
    │   │   ├── SagaRouteConfigTest.java                     (MOVED)
    │   │   └── KafkaCommandMapperTest.java                  (NEW)
    │   └── out/
    │       ├── persistence/
    │       │   ├── InvoicePdfDocumentRepositoryAdapterTest.java
    │       │   ├── InvoicePdfDocumentRepositoryIntegrationTest.java
    │       │   └── outbox/OutboxEventEntityTest.java
    │       ├── messaging/
    │       │   ├── EventPublisherTest.java
    │       │   └── SagaReplyPublisherTest.java
    │       ├── storage/MinioStorageAdapterTest.java
    │       ├── client/RestTemplateSignedXmlFetcherTest.java
    │       └── pdf/
    │           ├── FopInvoicePdfGeneratorTest.java
    │           ├── PdfA3ConverterTest.java
    │           └── InvoicePdfGenerationServiceImplTest.java
    └── ApplicationContextLoadTest.java
```

### Coverage Gates

| Scope | Target |
|-------|--------|
| `domain/` | 95%+ line coverage |
| `application/` | 95%+ line coverage |
| `infrastructure/adapter/` | 90%+ line coverage (JaCoCo enforced via `mvn verify`) |

---

## Migration Checklist

### Phase 1 — Domain Cleanup
- [ ] Add `domain/exception/InvoicePdfGenerationException.java`
- [ ] Replace `IllegalStateException` throws in `InvoicePdfDocument` with `InvoicePdfGenerationException`
- [ ] Keep `domain/repository/InvoicePdfDocumentRepository.java` in place (no move)
- [ ] Remove `domain/event/` package (all 4 classes relocate — see Phase 3 & 4)

### Phase 2 — Application Inbound Ports
- [ ] Create `application/usecase/ProcessInvoicePdfUseCase.java`
- [ ] Create `application/usecase/CompensateInvoicePdfUseCase.java`
- [ ] Update `SagaCommandHandler` to implement both use-case interfaces
- [ ] Update `InvoicePdfDocumentService` imports (`InvoicePdfDocumentRepository` stays same FQCN)

### Phase 3 — Kafka Inbound Adapter
- [ ] Create `infrastructure/adapter/in/kafka/` package
- [ ] Move + rename `ProcessInvoicePdfCommand` → `KafkaProcessInvoicePdfCommand`
- [ ] Move + rename `CompensateInvoicePdfCommand` → `KafkaCompensateInvoicePdfCommand`
- [ ] Create `KafkaCommandMapper`
- [ ] Move `SagaRouteConfig` → `infrastructure/adapter/in/kafka/`; inject use-case interfaces

### Phase 4 — Outbound Adapters Restructure
- [ ] Move `infrastructure/persistence/` → `infrastructure/adapter/out/persistence/`
- [ ] Move `infrastructure/messaging/` → `infrastructure/adapter/out/messaging/`
- [ ] Move `InvoicePdfReplyEvent` + `InvoicePdfGeneratedEvent` (from `domain/event/`) → `infrastructure/adapter/out/messaging/`
- [ ] Move `infrastructure/storage/` → `infrastructure/adapter/out/storage/`
- [ ] Move `infrastructure/client/` → `infrastructure/adapter/out/client/`
- [ ] Move `infrastructure/pdf/` → `infrastructure/adapter/out/pdf/`

### Phase 5 — Config Cleanup
- [ ] Move `MinioConfig` + `OutboxConfig` to `infrastructure/config/` (already there — verify no Camel config remains)
- [ ] Verify `infrastructure/config/` contains only bean factories (no routing logic)

### Phase 6 — Test Migration
- [ ] Mirror all package moves in `src/test/java/`
- [ ] Add `KafkaCommandMapperTest`
- [ ] Add `InvoicePdfGenerationExceptionTest`
- [ ] Run `mvn verify` — confirm 90% JaCoCo gate passes

---

## Files Changed Summary

| Action | Count |
|--------|-------|
| New classes | 5 (`ProcessInvoicePdfUseCase`, `CompensateInvoicePdfUseCase`, `KafkaCommandMapper`, `InvoicePdfGenerationException`, `KafkaCommandMapperTest`, `InvoicePdfGenerationExceptionTest`) |
| Moved + renamed | 4 (`ProcessInvoicePdfCommand` → `KafkaProcessInvoicePdfCommand`, `CompensateInvoicePdfCommand` → `KafkaCompensateInvoicePdfCommand`, `InvoicePdfReplyEvent`, `InvoicePdfGeneratedEvent`) |
| Moved only (no logic change) | ~15 (all infrastructure classes) |
| Import updates only | ~8 (application services, test classes) |

---

## Package Naming Conventions

### Port Locations

**Inbound Ports (Driving Adapters):** `application/usecase/`
- Use-case interfaces define the "driving side" of the hexagon
- Examples: `ProcessInvoicePdfUseCase`, `CompensateInvoicePdfUseCase`
- These are implemented by application services (e.g., `SagaCommandHandler`)

**Outbound Ports (Driven Adapters):** `application/port/out/`
- Port interfaces for dependencies that the application needs from the outside world
- Examples: `PdfStoragePort`, `SagaReplyPort`, `SignedXmlFetchPort`

**Domain-Owned Outbound Ports:** `domain/repository/`
- Repository interfaces that belong to the domain ubiquitous language
- Example: `InvoicePdfDocumentRepository` (stays in `domain/repository/`)

### Adapter Locations

**Inbound Adapters:** `infrastructure/adapter/in/`
- External systems that drive our application
- Examples: Kafka consumers (`kafka/`), REST controllers (not present in this service)

**Outbound Adapters:** `infrastructure/adapter/out/`
- Implementations that our application uses to interact with the outside world
- Organized by technology/purpose:
  - `client/` - External HTTP clients
  - `messaging/` - Outbound messaging (Kafka producers via outbox)
  - `pdf/` - PDF generation libraries
  - `persistence/` - Database adapters
  - `storage/` - Object storage adapters

**Configuration:** `infrastructure/config/`
- Spring `@Configuration` classes for bean definitions
- Only bean factories and wiring — no business logic

### Summary Diagram

```
                    ┌─────────────────────────────────────┐
                    │         Kafka Topic               │
                    └─────────────────────────────────────┘
                                    │
                                    ▼
                    ┌──────────────────────────────────────┐
                    │  adapter/in/kafka/                  │  Inbound
                    │  - SagaRouteConfig                 │  Adapter
                    │  - Kafka*Command (wire DTOs)       │
                    │  - KafkaCommandMapper              │
                    └──────────────────────────────────────┘
                                    │
                                    ▼
                    ┌──────────────────────────────────────┐
                    │  application/usecase/               │  Inbound
                    │  - ProcessInvoicePdfUseCase         │  Port
                    │  - CompensateInvoicePdfUseCase      │
                    └──────────────────────────────────────┘
                                    │
                                    ▼
                    ┌──────────────────────────────────────┐
                    │  application/service/                │
                    │  - SagaCommandHandler               │
                    └──────────────────────────────────────┘
            ┌───────────────────────┼───────────────────────┐
            ▼                       ▼                       ▼
    ┌──────────────────┐   ┌──────────────────┐   ┌──────────────────┐
    │ port/out/       │   │ domain/          │   │ port/out/       │
    │ - PdfStoragePort│   │ - model/         │   │ - SagaReplyPort │
    │ - SignedXmlFetch│   │ - repository/    │   │                 │
    │                  │   │ - service/       │   │                 │
    └──────────────────┘   └──────────────────┘   └──────────────────┘
            │                       │                       │
            ▼                       ▼                       ▼
    ┌──────────────────┐   ┌──────────────────┐   ┌──────────────────┐
    │ adapter/out/     │   │ (domain stays    │   │ adapter/out/     │
    │ - storage/       │   │  pure - no       │   │ - messaging/     │
    │ - client/        │   │  imports)        │   │                 │
    │                  │   │                  │   │                 │
    └──────────────────┘   └──────────────────┘   └──────────────────┘
```
| Deleted packages | `domain/event/`, `infrastructure/persistence/`, `infrastructure/messaging/`, `infrastructure/storage/`, `infrastructure/client/`, `infrastructure/pdf/`, `infrastructure/config/SagaRouteConfig` |

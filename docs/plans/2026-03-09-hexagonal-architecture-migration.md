# Hexagonal Architecture Migration Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Migrate invoice-pdf-generation-service to full canonical Hexagonal Architecture with strict port/adapter pattern, enforcing the dependency rule end-to-end.

**Architecture:** Domain layer is pure Java with zero framework imports. Application layer owns inbound use-case ports (`application/usecase/`) and non-domain outbound ports (`application/port/out/`). Domain retains its own repository port (`domain/repository/`). All framework adapters live under `infrastructure/adapter/in/` (Kafka) and `infrastructure/adapter/out/` (persistence, messaging, storage, client, pdf). Kafka wire DTOs are removed from `domain/event/` and placed in their owning adapters.

**Tech Stack:** Java 21, Spring Boot 3.2.5, Apache Camel 4.14.4, Apache FOP 2.9, PDFBox 3.0.1, Kafka, PostgreSQL, MinIO, saga-commons, Lombok, MapStruct

**Design doc:** `docs/plans/2026-03-09-hexagonal-architecture-migration-design.md`

---

## Phase 1 — Domain Cleanup

### Task 1: Add `InvoicePdfGenerationException`

**Files:**
- Create: `src/main/java/com/wpanther/invoice/pdf/domain/exception/InvoicePdfGenerationException.java`
- Create: `src/test/java/com/wpanther/invoice/pdf/domain/exception/InvoicePdfGenerationExceptionTest.java`

**Step 1: Write the failing test**

```java
// src/test/java/com/wpanther/invoice/pdf/domain/exception/InvoicePdfGenerationExceptionTest.java
package com.wpanther.invoice.pdf.domain.exception;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class InvoicePdfGenerationExceptionTest {

    @Test
    void constructor_withMessage_storesMessage() {
        var ex = new InvoicePdfGenerationException("test error");
        assertThat(ex.getMessage()).isEqualTo("test error");
    }

    @Test
    void constructor_withMessageAndCause_storesBoth() {
        var cause = new RuntimeException("root");
        var ex = new InvoicePdfGenerationException("wrapped", cause);
        assertThat(ex.getMessage()).isEqualTo("wrapped");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void isRuntimeException() {
        assertThat(new InvoicePdfGenerationException("x"))
                .isInstanceOf(RuntimeException.class);
    }
}
```

**Step 2: Run to verify it fails**

```bash
cd /home/wpanther/projects/etax/invoice-microservices/services/invoice-pdf-generation-service
mvn test -Dtest=InvoicePdfGenerationExceptionTest -q 2>&1 | tail -5
```
Expected: FAIL — class not found.

**Step 3: Implement**

```java
// src/main/java/com/wpanther/invoice/pdf/domain/exception/InvoicePdfGenerationException.java
package com.wpanther.invoice.pdf.domain.exception;

public class InvoicePdfGenerationException extends RuntimeException {

    public InvoicePdfGenerationException(String message) {
        super(message);
    }

    public InvoicePdfGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

**Step 4: Run to verify it passes**

```bash
mvn test -Dtest=InvoicePdfGenerationExceptionTest -q 2>&1 | tail -5
```
Expected: BUILD SUCCESS.

**Step 5: Commit**

```bash
git add src/main/java/com/wpanther/invoice/pdf/domain/exception/InvoicePdfGenerationException.java \
        src/test/java/com/wpanther/invoice/pdf/domain/exception/InvoicePdfGenerationExceptionTest.java
git commit -m "feat: add InvoicePdfGenerationException to domain/exception"
```

---

### Task 2: Replace `IllegalStateException` in `InvoicePdfDocument` with `InvoicePdfGenerationException`

**Files:**
- Modify: `src/main/java/com/wpanther/invoice/pdf/domain/model/InvoicePdfDocument.java`
- Modify: `src/test/java/com/wpanther/invoice/pdf/domain/model/InvoicePdfDocumentTest.java`

**Step 1: Update tests to expect `InvoicePdfGenerationException`**

Open `InvoicePdfDocumentTest.java`. Find every `assertThrows(IllegalStateException.class, ...)` call and change it to `assertThrows(InvoicePdfGenerationException.class, ...)`. Add the import:

```java
import com.wpanther.invoice.pdf.domain.exception.InvoicePdfGenerationException;
```

**Step 2: Run to verify tests now fail**

```bash
mvn test -Dtest=InvoicePdfDocumentTest -q 2>&1 | tail -10
```
Expected: FAIL — tests expect `InvoicePdfGenerationException` but code throws `IllegalStateException`.

**Step 3: Update `InvoicePdfDocument`**

Add import at the top of `InvoicePdfDocument.java`:
```java
import com.wpanther.invoice.pdf.domain.exception.InvoicePdfGenerationException;
```

Replace every `throw new IllegalStateException(` with `throw new InvoicePdfGenerationException(` in `InvoicePdfDocument.java`. There are four locations:
- `validateInvariant()` — two throws
- `startGeneration()` — one throw
- `markCompleted()` — one throw
- `markFailed()` — one throw

**Step 4: Run to verify**

```bash
mvn test -Dtest=InvoicePdfDocumentTest -q 2>&1 | tail -5
```
Expected: BUILD SUCCESS.

**Step 5: Commit**

```bash
git add src/main/java/com/wpanther/invoice/pdf/domain/model/InvoicePdfDocument.java \
        src/test/java/com/wpanther/invoice/pdf/domain/model/InvoicePdfDocumentTest.java
git commit -m "refactor: use InvoicePdfGenerationException in domain aggregate"
```

---

## Phase 2 — Application Inbound Ports (use cases)

### Task 3: Create `ProcessInvoicePdfUseCase` and `CompensateInvoicePdfUseCase`

The use-case interfaces accept the Kafka wire DTOs because the DTOs extend `SagaCommand` (saga-commons). The alternative — wrapping commands in plain records — would add a mapping layer with zero benefit since the commands are already owned by the adapter after Phase 3. The interfaces live in `application/usecase/` and import from `infrastructure/adapter/in/kafka/` only after Task 5 renames the DTOs. For now we keep the existing `domain/event/` imports; they are cleaned up in Task 6.

> **Note:** The import of the command classes into `application/usecase/` will temporarily reference `domain/event/`. This is intentional — the final clean import to `infrastructure/adapter/in/kafka/` is wired up after Task 5.

**Files:**
- Create: `src/main/java/com/wpanther/invoice/pdf/application/usecase/ProcessInvoicePdfUseCase.java`
- Create: `src/main/java/com/wpanther/invoice/pdf/application/usecase/CompensateInvoicePdfUseCase.java`

**Step 1: Create the interfaces (no test needed — pure interfaces)**

```java
// src/main/java/com/wpanther/invoice/pdf/application/usecase/ProcessInvoicePdfUseCase.java
package com.wpanther.invoice.pdf.application.usecase;

import com.wpanther.invoice.pdf.domain.event.ProcessInvoicePdfCommand;

public interface ProcessInvoicePdfUseCase {
    void handle(ProcessInvoicePdfCommand command);
}
```

```java
// src/main/java/com/wpanther/invoice/pdf/application/usecase/CompensateInvoicePdfUseCase.java
package com.wpanther.invoice.pdf.application.usecase;

import com.wpanther.invoice.pdf.domain.event.CompensateInvoicePdfCommand;

public interface CompensateInvoicePdfUseCase {
    void handle(CompensateInvoicePdfCommand command);
}
```

**Step 2: Verify compilation**

```bash
mvn compile -q 2>&1 | tail -5
```
Expected: BUILD SUCCESS.

**Step 3: Commit**

```bash
git add src/main/java/com/wpanther/invoice/pdf/application/usecase/
git commit -m "feat: add ProcessInvoicePdfUseCase and CompensateInvoicePdfUseCase inbound ports"
```

---

### Task 4: `SagaCommandHandler` implements both use-case interfaces

**Files:**
- Modify: `src/main/java/com/wpanther/invoice/pdf/application/service/SagaCommandHandler.java`
- Modify: `src/test/java/com/wpanther/invoice/pdf/application/service/SagaCommandHandlerTest.java`

**Step 1: Update `SagaCommandHandler` declaration**

Change line 40 from:
```java
public class SagaCommandHandler {
```
to:
```java
public class SagaCommandHandler implements ProcessInvoicePdfUseCase, CompensateInvoicePdfUseCase {
```

Add the imports at the top:
```java
import com.wpanther.invoice.pdf.application.usecase.CompensateInvoicePdfUseCase;
import com.wpanther.invoice.pdf.application.usecase.ProcessInvoicePdfUseCase;
```

Rename the two public handler methods to match the interface:
- `handleProcessCommand(ProcessInvoicePdfCommand command)` → `handle(ProcessInvoicePdfCommand command)` (implements `ProcessInvoicePdfUseCase`)
- `handleCompensation(CompensateInvoicePdfCommand command)` → `handle(CompensateInvoicePdfCommand command)` (implements `CompensateInvoicePdfUseCase`)

Add `@Override` to both renamed methods.

**Step 2: Update `SagaRouteConfig` call sites**

In `SagaRouteConfig.java` change:
```java
sagaCommandHandler.handleProcessCommand(cmd);
```
to:
```java
sagaCommandHandler.handle(cmd);
```
And:
```java
sagaCommandHandler.handleCompensation(cmd);
```
to:
```java
sagaCommandHandler.handle(cmd);
```

**Step 3: Update `SagaCommandHandlerTest` call sites**

Find every `handler.handleProcessCommand(` → `handler.handle(` and every `handler.handleCompensation(` → `handler.handle(` in `SagaCommandHandlerTest.java`.

**Step 4: Run tests**

```bash
mvn test -Dtest=SagaCommandHandlerTest -q 2>&1 | tail -10
```
Expected: BUILD SUCCESS.

**Step 5: Commit**

```bash
git add src/main/java/com/wpanther/invoice/pdf/application/service/SagaCommandHandler.java \
        src/main/java/com/wpanther/invoice/pdf/infrastructure/config/SagaRouteConfig.java \
        src/test/java/com/wpanther/invoice/pdf/application/service/SagaCommandHandlerTest.java
git commit -m "refactor: SagaCommandHandler implements ProcessInvoicePdfUseCase + CompensateInvoicePdfUseCase"
```

---

## Phase 3 — Kafka Inbound Adapter

### Task 5: Move Kafka wire DTOs and `SagaRouteConfig` to `infrastructure/adapter/in/kafka/`

This task moves four files and renames the two inbound command classes with a `Kafka` prefix.

**Files:**
- Create: `src/main/java/com/wpanther/invoice/pdf/infrastructure/adapter/in/kafka/KafkaProcessInvoicePdfCommand.java`
- Create: `src/main/java/com/wpanther/invoice/pdf/infrastructure/adapter/in/kafka/KafkaCompensateInvoicePdfCommand.java`
- Create: `src/main/java/com/wpanther/invoice/pdf/infrastructure/adapter/in/kafka/SagaRouteConfig.java` (moved)
- Create: `src/main/java/com/wpanther/invoice/pdf/infrastructure/adapter/in/kafka/KafkaCommandMapper.java` (NEW)
- Delete: `src/main/java/com/wpanther/invoice/pdf/infrastructure/config/SagaRouteConfig.java`
- Delete: `src/main/java/com/wpanther/invoice/pdf/domain/event/ProcessInvoicePdfCommand.java`
- Delete: `src/main/java/com/wpanther/invoice/pdf/domain/event/CompensateInvoicePdfCommand.java`

**Step 1: Write failing test for `KafkaCommandMapper`**

```java
// src/test/java/com/wpanther/invoice/pdf/infrastructure/adapter/in/kafka/KafkaCommandMapperTest.java
package com.wpanther.invoice.pdf.infrastructure.adapter.in.kafka;

import com.wpanther.saga.domain.enums.SagaStep;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class KafkaCommandMapperTest {

    private final KafkaCommandMapper mapper = new KafkaCommandMapper();

    @Test
    void toProcess_mapsAllFields() {
        var src = new KafkaProcessInvoicePdfCommand(
                null, null, null, 0,
                "saga-1", SagaStep.GENERATE_INVOICE_PDF, "corr-1",
                "doc-1", "inv-1", "INV-001",
                "http://minio/xml", "{\"key\":\"val\"}");

        var result = mapper.toProcess(src);

        assertThat(result.getSagaId()).isEqualTo("saga-1");
        assertThat(result.getCorrelationId()).isEqualTo("corr-1");
        assertThat(result.getDocumentId()).isEqualTo("doc-1");
        assertThat(result.getInvoiceId()).isEqualTo("inv-1");
        assertThat(result.getInvoiceNumber()).isEqualTo("INV-001");
        assertThat(result.getSignedXmlUrl()).isEqualTo("http://minio/xml");
        assertThat(result.getInvoiceDataJson()).isEqualTo("{\"key\":\"val\"}");
    }

    @Test
    void toCompensate_mapsAllFields() {
        var src = new KafkaCompensateInvoicePdfCommand(
                null, null, null, 0,
                "saga-2", SagaStep.GENERATE_INVOICE_PDF, "corr-2",
                "doc-2", "inv-2");

        var result = mapper.toCompensate(src);

        assertThat(result.getSagaId()).isEqualTo("saga-2");
        assertThat(result.getCorrelationId()).isEqualTo("corr-2");
        assertThat(result.getDocumentId()).isEqualTo("doc-2");
        assertThat(result.getInvoiceId()).isEqualTo("inv-2");
    }
}
```

**Step 2: Run to verify it fails**

```bash
mvn test -Dtest=KafkaCommandMapperTest -q 2>&1 | tail -5
```
Expected: FAIL — classes not found.

**Step 3: Create `KafkaProcessInvoicePdfCommand`**

Copy content of `domain/event/ProcessInvoicePdfCommand.java`, change package to `com.wpanther.invoice.pdf.infrastructure.adapter.in.kafka`, rename class to `KafkaProcessInvoicePdfCommand`:

```java
// src/main/java/com/wpanther/invoice/pdf/infrastructure/adapter/in/kafka/KafkaProcessInvoicePdfCommand.java
package com.wpanther.invoice.pdf.infrastructure.adapter.in.kafka;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.saga.domain.model.SagaCommand;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Getter
public class KafkaProcessInvoicePdfCommand extends SagaCommand {

    private static final long serialVersionUID = 1L;

    @JsonProperty("documentId")
    private final String documentId;

    @JsonProperty("invoiceId")
    private final String invoiceId;

    @JsonProperty("invoiceNumber")
    private final String invoiceNumber;

    @JsonProperty("signedXmlUrl")
    private final String signedXmlUrl;

    @JsonProperty("invoiceDataJson")
    private final String invoiceDataJson;

    @JsonCreator
    public KafkaProcessInvoicePdfCommand(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("version") int version,
            @JsonProperty("sagaId") String sagaId,
            @JsonProperty("sagaStep") SagaStep sagaStep,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("documentId") String documentId,
            @JsonProperty("invoiceId") String invoiceId,
            @JsonProperty("invoiceNumber") String invoiceNumber,
            @JsonProperty("signedXmlUrl") String signedXmlUrl,
            @JsonProperty("invoiceDataJson") String invoiceDataJson) {
        super(eventId, occurredAt, eventType, version, sagaId, sagaStep, correlationId);
        this.documentId = documentId;
        this.invoiceId = invoiceId;
        this.invoiceNumber = invoiceNumber;
        this.signedXmlUrl = signedXmlUrl;
        this.invoiceDataJson = invoiceDataJson;
    }

    public KafkaProcessInvoicePdfCommand(String sagaId, SagaStep sagaStep, String correlationId,
                                         String documentId, String invoiceId, String invoiceNumber,
                                         String signedXmlUrl, String invoiceDataJson) {
        super(sagaId, sagaStep, correlationId);
        this.documentId = Objects.requireNonNull(documentId, "documentId is required");
        this.invoiceId = Objects.requireNonNull(invoiceId, "invoiceId is required");
        this.invoiceNumber = Objects.requireNonNull(invoiceNumber, "invoiceNumber is required");
        this.signedXmlUrl = Objects.requireNonNull(signedXmlUrl, "signedXmlUrl is required");
        this.invoiceDataJson = Objects.requireNonNull(invoiceDataJson, "invoiceDataJson is required");
    }
}
```

**Step 4: Create `KafkaCompensateInvoicePdfCommand`**

```java
// src/main/java/com/wpanther/invoice/pdf/infrastructure/adapter/in/kafka/KafkaCompensateInvoicePdfCommand.java
package com.wpanther.invoice.pdf.infrastructure.adapter.in.kafka;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.saga.domain.model.SagaCommand;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Getter
public class KafkaCompensateInvoicePdfCommand extends SagaCommand {

    private static final long serialVersionUID = 1L;

    @JsonProperty("documentId")
    private final String documentId;

    @JsonProperty("invoiceId")
    private final String invoiceId;

    @JsonCreator
    public KafkaCompensateInvoicePdfCommand(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("version") int version,
            @JsonProperty("sagaId") String sagaId,
            @JsonProperty("sagaStep") SagaStep sagaStep,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("documentId") String documentId,
            @JsonProperty("invoiceId") String invoiceId) {
        super(eventId, occurredAt, eventType, version, sagaId, sagaStep, correlationId);
        this.documentId = documentId;
        this.invoiceId = invoiceId;
    }

    public KafkaCompensateInvoicePdfCommand(String sagaId, SagaStep sagaStep, String correlationId,
                                            String documentId, String invoiceId) {
        super(sagaId, sagaStep, correlationId);
        this.documentId = Objects.requireNonNull(documentId, "documentId is required");
        this.invoiceId = Objects.requireNonNull(invoiceId, "invoiceId is required");
    }
}
```

**Step 5: Create `KafkaCommandMapper`**

```java
// src/main/java/com/wpanther/invoice/pdf/infrastructure/adapter/in/kafka/KafkaCommandMapper.java
package com.wpanther.invoice.pdf.infrastructure.adapter.in.kafka;

import com.wpanther.invoice.pdf.domain.event.CompensateInvoicePdfCommand;
import com.wpanther.invoice.pdf.domain.event.ProcessInvoicePdfCommand;
import org.springframework.stereotype.Component;

@Component
public class KafkaCommandMapper {

    public ProcessInvoicePdfCommand toProcess(KafkaProcessInvoicePdfCommand src) {
        return new ProcessInvoicePdfCommand(
                src.getSagaId(), src.getSagaStep(), src.getCorrelationId(),
                src.getDocumentId(), src.getInvoiceId(), src.getInvoiceNumber(),
                src.getSignedXmlUrl(), src.getInvoiceDataJson());
    }

    public CompensateInvoicePdfCommand toCompensate(KafkaCompensateInvoicePdfCommand src) {
        return new CompensateInvoicePdfCommand(
                src.getSagaId(), src.getSagaStep(), src.getCorrelationId(),
                src.getDocumentId(), src.getInvoiceId());
    }
}
```

**Step 6: Run mapper test**

```bash
mvn test -Dtest=KafkaCommandMapperTest -q 2>&1 | tail -5
```
Expected: BUILD SUCCESS.

**Step 7: Create new `SagaRouteConfig` in `adapter/in/kafka/`**

Copy `infrastructure/config/SagaRouteConfig.java`, change the package declaration to:
```java
package com.wpanther.invoice.pdf.infrastructure.adapter.in.kafka;
```
Update imports: replace `com.wpanther.invoice.pdf.domain.event.ProcessInvoicePdfCommand` with `com.wpanther.invoice.pdf.infrastructure.adapter.in.kafka.KafkaProcessInvoicePdfCommand`, and similarly for `CompensateInvoicePdfCommand`.

Update the constructor to also accept `KafkaCommandMapper`:
```java
private final ProcessInvoicePdfUseCase processUseCase;
private final CompensateInvoicePdfUseCase compensateUseCase;
private final KafkaCommandMapper commandMapper;
private final ObjectMapper objectMapper;

public SagaRouteConfig(ProcessInvoicePdfUseCase processUseCase,
                       CompensateInvoicePdfUseCase compensateUseCase,
                       KafkaCommandMapper commandMapper,
                       ObjectMapper objectMapper) {
    this.processUseCase = processUseCase;
    this.compensateUseCase = compensateUseCase;
    this.commandMapper = commandMapper;
    this.objectMapper = objectMapper;
}
```

In the `configure()` method:
- Change `.unmarshal().json(JsonLibrary.Jackson, ProcessInvoicePdfCommand.class)` → `.unmarshal().json(JsonLibrary.Jackson, KafkaProcessInvoicePdfCommand.class)`
- Change `.unmarshal().json(JsonLibrary.Jackson, CompensateInvoicePdfCommand.class)` → `.unmarshal().json(JsonLibrary.Jackson, KafkaCompensateInvoicePdfCommand.class)`
- In the process exchange lambda, map and dispatch via use-case:
  ```java
  KafkaProcessInvoicePdfCommand raw = exchange.getIn().getBody(KafkaProcessInvoicePdfCommand.class);
  processUseCase.handle(commandMapper.toProcess(raw));
  ```
  And for compensation:
  ```java
  KafkaCompensateInvoicePdfCommand raw = exchange.getIn().getBody(KafkaCompensateInvoicePdfCommand.class);
  compensateUseCase.handle(commandMapper.toCompensate(raw));
  ```
- Change the `onPrepareFailure` body checks from `ProcessInvoicePdfCommand` / `CompensateInvoicePdfCommand` to `KafkaProcessInvoicePdfCommand` / `KafkaCompensateInvoicePdfCommand`, and inject `SagaCommandHandler` (cast from `ProcessInvoicePdfUseCase`) for the DLQ methods, or keep a `SagaCommandHandler` reference for the three `publishOrchestrationFailure*` methods. The cleanest approach: keep a `SagaCommandHandler sagaCommandHandler` field in addition to the use-case fields (since `publishOrchestrationFailure` is not part of the use-case interface).

  Full updated constructor:
  ```java
  private final ProcessInvoicePdfUseCase processUseCase;
  private final CompensateInvoicePdfUseCase compensateUseCase;
  private final SagaCommandHandler sagaCommandHandler;
  private final KafkaCommandMapper commandMapper;
  private final ObjectMapper objectMapper;

  public SagaRouteConfig(ProcessInvoicePdfUseCase processUseCase,
                         CompensateInvoicePdfUseCase compensateUseCase,
                         SagaCommandHandler sagaCommandHandler,
                         KafkaCommandMapper commandMapper,
                         ObjectMapper objectMapper) {
      this.processUseCase = processUseCase;
      this.compensateUseCase = compensateUseCase;
      this.sagaCommandHandler = sagaCommandHandler;
      this.commandMapper = commandMapper;
      this.objectMapper = objectMapper;
  }
  ```

  And in `onPrepareFailure`:
  ```java
  if (body instanceof KafkaProcessInvoicePdfCommand raw) {
      sagaCommandHandler.publishOrchestrationFailure(commandMapper.toProcess(raw), cause);
  } else if (body instanceof KafkaCompensateInvoicePdfCommand raw) {
      sagaCommandHandler.publishCompensationOrchestrationFailure(commandMapper.toCompensate(raw), cause);
  } else { ... }
  ```

**Step 8: Delete old `infrastructure/config/SagaRouteConfig.java`**

```bash
git rm src/main/java/com/wpanther/invoice/pdf/infrastructure/config/SagaRouteConfig.java
```

**Step 9: Run `SagaRouteConfigTest` — update test package reference if needed**

The existing `SagaRouteConfigTest` is in `infrastructure/config/`. Move it to `infrastructure/adapter/in/kafka/` (rename the file, update the package declaration and imports).

```bash
mvn test -Dtest=SagaRouteConfigTest -q 2>&1 | tail -10
```
Expected: BUILD SUCCESS.

**Step 10: Full test suite**

```bash
mvn test -q 2>&1 | tail -10
```
Expected: BUILD SUCCESS — all tests pass.

**Step 11: Commit**

```bash
git add src/main/java/com/wpanther/invoice/pdf/infrastructure/adapter/in/
git add src/test/java/com/wpanther/invoice/pdf/infrastructure/adapter/in/
git commit -m "refactor: move Kafka DTOs and SagaRouteConfig to adapter/in/kafka"
```

---

### Task 6: Update use-case interface imports to reference `adapter/in/kafka/` DTOs; delete `domain/event/` Kafka classes

After Task 5, `application/usecase/` still imports from `domain/event/`. Now that the Kafka DTOs live in `infrastructure/adapter/in/kafka/`, we update the imports.

> **Architectural note:** The use-case interfaces import from `infrastructure/adapter/in/kafka/`. This is acceptable in this design: the command objects are the input contract of the use case, and since there is a single Kafka driver, the command type is co-located with the adapter that produces it. If a second driver were added in future, a plain record in `application/usecase/` would be preferred.

**Files:**
- Modify: `src/main/java/com/wpanther/invoice/pdf/application/usecase/ProcessInvoicePdfUseCase.java`
- Modify: `src/main/java/com/wpanther/invoice/pdf/application/usecase/CompensateInvoicePdfUseCase.java`
- Modify: `src/main/java/com/wpanther/invoice/pdf/application/service/SagaCommandHandler.java`
- Modify: `src/main/java/com/wpanther/invoice/pdf/application/service/InvoicePdfDocumentService.java`
- Delete: `src/main/java/com/wpanther/invoice/pdf/domain/event/ProcessInvoicePdfCommand.java`
- Delete: `src/main/java/com/wpanther/invoice/pdf/domain/event/CompensateInvoicePdfCommand.java`

**Step 1: Update `ProcessInvoicePdfUseCase` import**

```java
// Change:
import com.wpanther.invoice.pdf.domain.event.ProcessInvoicePdfCommand;
// To:
import com.wpanther.invoice.pdf.infrastructure.adapter.in.kafka.KafkaProcessInvoicePdfCommand;
```
Rename the parameter type in the method signature to `KafkaProcessInvoicePdfCommand`.

**Step 2: Update `CompensateInvoicePdfUseCase` import**

```java
import com.wpanther.invoice.pdf.infrastructure.adapter.in.kafka.KafkaCompensateInvoicePdfCommand;
```

**Step 3: Update `SagaCommandHandler`**

Replace all `domain.event.ProcessInvoicePdfCommand` imports with `infrastructure.adapter.in.kafka.KafkaProcessInvoicePdfCommand` and similarly for compensate. Rename all method parameter types accordingly. The three `publishOrchestrationFailure*` methods also take the Kafka command types — update them too.

**Step 4: Update `InvoicePdfDocumentService`**

Replace:
```java
import com.wpanther.invoice.pdf.domain.event.CompensateInvoicePdfCommand;
import com.wpanther.invoice.pdf.domain.event.ProcessInvoicePdfCommand;
```
With:
```java
import com.wpanther.invoice.pdf.infrastructure.adapter.in.kafka.KafkaCompensateInvoicePdfCommand;
import com.wpanther.invoice.pdf.infrastructure.adapter.in.kafka.KafkaProcessInvoicePdfCommand;
```
Rename all method parameter types accordingly throughout the file.

**Step 5: Delete the old `domain/event/` Kafka command files**

```bash
git rm src/main/java/com/wpanther/invoice/pdf/domain/event/ProcessInvoicePdfCommand.java
git rm src/main/java/com/wpanther/invoice/pdf/domain/event/CompensateInvoicePdfCommand.java
```

**Step 6: Update all test files**

Find all test files that import `domain.event.ProcessInvoicePdfCommand` or `domain.event.CompensateInvoicePdfCommand`:

```bash
grep -rl "domain.event.ProcessInvoicePdfCommand\|domain.event.CompensateInvoicePdfCommand" src/test/
```

Update each import and constructor call to reference `KafkaProcessInvoicePdfCommand` / `KafkaCompensateInvoicePdfCommand` from `infrastructure.adapter.in.kafka`.

**Step 7: Compile and test**

```bash
mvn test -q 2>&1 | tail -10
```
Expected: BUILD SUCCESS.

**Step 8: Commit**

```bash
git add -u
git commit -m "refactor: remove domain/event Kafka DTOs, wire use-cases to adapter/in/kafka types"
```

---

## Phase 4 — Outbound Adapters Restructure

> All moves in this phase are package renames only — no logic changes. The pattern is: create the new file in the correct `adapter/out/` sub-package, update its `package` declaration and any cross-file imports, delete the old file.

### Task 7: Move `infrastructure/messaging/` → `infrastructure/adapter/out/messaging/`

Move outbound Kafka wire DTOs (`InvoicePdfReplyEvent`, `InvoicePdfGeneratedEvent`) from `domain/event/` into this package at the same time.

**Files to move (messaging):**
- `infrastructure/messaging/EventPublisher.java` → `infrastructure/adapter/out/messaging/EventPublisher.java`
- `infrastructure/messaging/SagaReplyPublisher.java` → `infrastructure/adapter/out/messaging/SagaReplyPublisher.java`
- `infrastructure/messaging/MessagingUtils.java` → `infrastructure/adapter/out/messaging/MessagingUtils.java`

**Files to move (from domain/event/):**
- `domain/event/InvoicePdfReplyEvent.java` → `infrastructure/adapter/out/messaging/InvoicePdfReplyEvent.java`
- `domain/event/InvoicePdfGeneratedEvent.java` → `infrastructure/adapter/out/messaging/InvoicePdfGeneratedEvent.java`

**Step 1: Create new files**

For each file, copy content, update `package` to `com.wpanther.invoice.pdf.infrastructure.adapter.out.messaging`.

For `EventPublisher.java` and `SagaReplyPublisher.java`, update the import of `InvoicePdfReplyEvent` and `InvoicePdfGeneratedEvent` from `domain.event` to `infrastructure.adapter.out.messaging`.

**Step 2: Delete old files**

```bash
git rm src/main/java/com/wpanther/invoice/pdf/infrastructure/messaging/EventPublisher.java
git rm src/main/java/com/wpanther/invoice/pdf/infrastructure/messaging/SagaReplyPublisher.java
git rm src/main/java/com/wpanther/invoice/pdf/infrastructure/messaging/MessagingUtils.java
git rm src/main/java/com/wpanther/invoice/pdf/domain/event/InvoicePdfReplyEvent.java
git rm src/main/java/com/wpanther/invoice/pdf/domain/event/InvoicePdfGeneratedEvent.java
```

**Step 3: Move test files**

Move:
- `infrastructure/messaging/EventPublisherTest.java` → `infrastructure/adapter/out/messaging/EventPublisherTest.java`
- `infrastructure/messaging/SagaReplyPublisherTest.java` → `infrastructure/adapter/out/messaging/SagaReplyPublisherTest.java`

Update their `package` declarations and imports.

Also update `InvoicePdfDocumentService.java` import for `InvoicePdfGeneratedEvent`:
```java
// Change:
import com.wpanther.invoice.pdf.domain.event.InvoicePdfGeneratedEvent;
// To:
import com.wpanther.invoice.pdf.infrastructure.adapter.out.messaging.InvoicePdfGeneratedEvent;
```

**Step 4: Delete empty `domain/event/` package** (it should now be empty)

```bash
git rm -r src/main/java/com/wpanther/invoice/pdf/domain/event/ 2>/dev/null || true
```

**Step 5: Test**

```bash
mvn test -q 2>&1 | tail -10
```
Expected: BUILD SUCCESS.

**Step 6: Commit**

```bash
git add -u
git add src/main/java/com/wpanther/invoice/pdf/infrastructure/adapter/out/messaging/
git add src/test/java/com/wpanther/invoice/pdf/infrastructure/adapter/out/messaging/
git commit -m "refactor: move messaging adapters and outbound Kafka DTOs to adapter/out/messaging"
```

---

### Task 8: Move `infrastructure/persistence/` → `infrastructure/adapter/out/persistence/`

**Files to move:**
- `InvoicePdfDocumentEntity.java`
- `InvoicePdfDocumentRepositoryAdapter.java`
- `JpaInvoicePdfDocumentRepository.java`
- `outbox/OutboxEventEntity.java`
- `outbox/JpaOutboxEventRepository.java`
- `outbox/SpringDataOutboxRepository.java`

**Step 1: Create new files in `infrastructure/adapter/out/persistence/`**

For each file: copy content, update `package` to `com.wpanther.invoice.pdf.infrastructure.adapter.out.persistence` (and `...outbox` for outbox files). Update any cross-file imports between persistence classes.

**Step 2: Update `OutboxConfig.java`**

`OutboxConfig` imports `SpringDataOutboxRepository`. Update import to the new package.

**Step 3: Delete old `infrastructure/persistence/` tree**

```bash
git rm -r src/main/java/com/wpanther/invoice/pdf/infrastructure/persistence/
```

**Step 4: Move test files**

Move the four persistence test files from `infrastructure/persistence/` to `infrastructure/adapter/out/persistence/`. Update `package` declarations and imports.

**Step 5: Test**

```bash
mvn test -q 2>&1 | tail -10
```
Expected: BUILD SUCCESS.

**Step 6: Commit**

```bash
git add -u
git add src/main/java/com/wpanther/invoice/pdf/infrastructure/adapter/out/persistence/
git add src/test/java/com/wpanther/invoice/pdf/infrastructure/adapter/out/persistence/
git commit -m "refactor: move persistence adapters to adapter/out/persistence"
```

---

### Task 9: Move `infrastructure/storage/` → `infrastructure/adapter/out/storage/`

**Files:**
- `MinioStorageAdapter.java` → `infrastructure/adapter/out/storage/MinioStorageAdapter.java`

**Step 1: Create new file**

Copy, update `package` to `com.wpanther.invoice.pdf.infrastructure.adapter.out.storage`.

**Step 2: Delete old file**

```bash
git rm src/main/java/com/wpanther/invoice/pdf/infrastructure/storage/MinioStorageAdapter.java
```

**Step 3: Move test file**

Move `infrastructure/storage/MinioStorageAdapterTest.java` → `infrastructure/adapter/out/storage/MinioStorageAdapterTest.java`. Update package declaration.

**Step 4: Test**

```bash
mvn test -Dtest=MinioStorageAdapterTest -q 2>&1 | tail -5
```
Expected: BUILD SUCCESS.

**Step 5: Commit**

```bash
git add -u
git add src/main/java/com/wpanther/invoice/pdf/infrastructure/adapter/out/storage/
git add src/test/java/com/wpanther/invoice/pdf/infrastructure/adapter/out/storage/
git commit -m "refactor: move MinioStorageAdapter to adapter/out/storage"
```

---

### Task 10: Move `infrastructure/client/` → `infrastructure/adapter/out/client/`

**Files:**
- `RestTemplateSignedXmlFetcher.java` → `infrastructure/adapter/out/client/RestTemplateSignedXmlFetcher.java`

**Step 1: Create new file**

Copy, update `package` to `com.wpanther.invoice.pdf.infrastructure.adapter.out.client`.

**Step 2: Delete old file**

```bash
git rm src/main/java/com/wpanther/invoice/pdf/infrastructure/client/RestTemplateSignedXmlFetcher.java
```

**Step 3: Move test file**

Move `infrastructure/client/RestTemplateSignedXmlFetcherTest.java` → `infrastructure/adapter/out/client/RestTemplateSignedXmlFetcherTest.java`. Update package declaration.

**Step 4: Test**

```bash
mvn test -Dtest=RestTemplateSignedXmlFetcherTest -q 2>&1 | tail -5
```
Expected: BUILD SUCCESS.

**Step 5: Commit**

```bash
git add -u
git add src/main/java/com/wpanther/invoice/pdf/infrastructure/adapter/out/client/
git add src/test/java/com/wpanther/invoice/pdf/infrastructure/adapter/out/client/
git commit -m "refactor: move RestTemplateSignedXmlFetcher to adapter/out/client"
```

---

### Task 11: Move `infrastructure/pdf/` → `infrastructure/adapter/out/pdf/`

**Files:**
- `FopInvoicePdfGenerator.java`
- `PdfA3Converter.java`
- `InvoicePdfGenerationServiceImpl.java`

**Step 1: Create new files in `infrastructure/adapter/out/pdf/`**

Copy each file, update `package` to `com.wpanther.invoice.pdf.infrastructure.adapter.out.pdf`.

**Step 2: Delete old files**

```bash
git rm -r src/main/java/com/wpanther/invoice/pdf/infrastructure/pdf/
```

**Step 3: Move test files**

Move `infrastructure/pdf/FopInvoicePdfGeneratorTest.java`, `PdfA3ConverterTest.java`, `InvoicePdfGenerationServiceImplTest.java` to `infrastructure/adapter/out/pdf/`. Update package declarations.

**Step 4: Test**

```bash
mvn test -Dtest="FopInvoicePdfGeneratorTest+PdfA3ConverterTest+InvoicePdfGenerationServiceImplTest" -q 2>&1 | tail -10
```
Expected: BUILD SUCCESS.

**Step 5: Commit**

```bash
git add -u
git add src/main/java/com/wpanther/invoice/pdf/infrastructure/adapter/out/pdf/
git add src/test/java/com/wpanther/invoice/pdf/infrastructure/adapter/out/pdf/
git commit -m "refactor: move PDF generation adapters to adapter/out/pdf"
```

---

## Phase 5 — Config Cleanup

### Task 12: Verify `infrastructure/config/` contains only bean factories

After Phase 3 removed `SagaRouteConfig`, only `MinioConfig.java` and `OutboxConfig.java` should remain in `infrastructure/config/`. No further moves are needed.

**Step 1: Verify**

```bash
ls src/main/java/com/wpanther/invoice/pdf/infrastructure/config/
```
Expected: `MinioConfig.java  OutboxConfig.java`

**Step 2: Verify no old packages remain**

```bash
find src/main/java/com/wpanther/invoice/pdf/infrastructure \
  -mindepth 1 -maxdepth 1 -type d | sort
```
Expected output:
```
src/main/java/com/wpanther/invoice/pdf/infrastructure/adapter
src/main/java/com/wpanther/invoice/pdf/infrastructure/config
```

If any old directories remain (e.g., `messaging/`, `persistence/`, `pdf/`, `storage/`, `client/`), remove them:
```bash
git rm -r src/main/java/com/wpanther/invoice/pdf/infrastructure/<old-dir>/
```

**Step 3: Commit if any cleanup was needed**

```bash
git add -u
git commit -m "chore: remove empty infrastructure sub-packages after adapter restructure"
```

---

## Phase 6 — Full Verification

### Task 13: Run full test suite with coverage gate

**Step 1: Run all tests with JaCoCo**

```bash
mvn verify -q 2>&1 | tail -20
```
Expected: BUILD SUCCESS with all JaCoCo line coverage >= 90% per package.

**Step 2: If coverage fails, identify gaps**

```bash
mvn verify 2>&1 | grep -A3 "Coverage check"
```

Common causes:
- New `KafkaCommandMapper` is not fully covered → add edge-case test in `KafkaCommandMapperTest`
- New `InvoicePdfGenerationException` constructor paths not covered → add test in `InvoicePdfGenerationExceptionTest`

**Step 3: Fix any failing tests from import drift**

```bash
mvn test 2>&1 | grep "FAIL\|ERROR" | head -20
```

Fix import paths in any failing test by searching for old package references:
```bash
grep -rl "domain\.event\." src/test/
grep -rl "infrastructure\.config\.SagaRouteConfig" src/test/
grep -rl "infrastructure\.messaging\." src/test/
grep -rl "infrastructure\.persistence\." src/test/
grep -rl "infrastructure\.pdf\." src/test/
grep -rl "infrastructure\.storage\." src/test/
grep -rl "infrastructure\.client\." src/test/
```

**Step 4: Verify Spring context loads**

```bash
mvn test -Dtest=ApplicationContextLoadTest -q 2>&1 | tail -5
```
Expected: BUILD SUCCESS.

**Step 5: Final commit**

```bash
git add -u
git commit -m "test: fix coverage gaps after hexagonal architecture migration"
```

---

### Task 14: Final structural verification

**Step 1: Verify no `domain` class imports from `infrastructure` or `application`**

```bash
grep -r "import com.wpanther.invoice.pdf.infrastructure\|import com.wpanther.invoice.pdf.application" \
  src/main/java/com/wpanther/invoice/pdf/domain/ 2>/dev/null
```
Expected: no output (empty).

**Step 2: Verify no `application` class imports from `infrastructure`**

```bash
grep -r "import com.wpanther.invoice.pdf.infrastructure" \
  src/main/java/com/wpanther/invoice/pdf/application/ 2>/dev/null
```
Expected: only `application/usecase/` files may reference `infrastructure.adapter.in.kafka` for command types (per design decision). If any `application/service/` files show infrastructure imports, fix them.

**Step 3: Verify final tree**

```bash
find src/main/java/com/wpanther/invoice/pdf -name "*.java" | sort
```
Expected structure matches `docs/plans/2026-03-09-hexagonal-architecture-migration-design.md`.

**Step 4: Final commit tag**

```bash
git commit --allow-empty -m "chore: hexagonal architecture migration complete"
```

---

## Summary of Changes

| Action | Files |
|--------|-------|
| New (domain) | `InvoicePdfGenerationException` |
| New (application) | `ProcessInvoicePdfUseCase`, `CompensateInvoicePdfUseCase` |
| New (adapter/in) | `KafkaProcessInvoicePdfCommand`, `KafkaCompensateInvoicePdfCommand`, `KafkaCommandMapper` |
| Moved + renamed | `ProcessInvoicePdfCommand` → `KafkaProcessInvoicePdfCommand`, `CompensateInvoicePdfCommand` → `KafkaCompensateInvoicePdfCommand` |
| Moved (outbound Kafka DTOs) | `InvoicePdfReplyEvent`, `InvoicePdfGeneratedEvent` → `adapter/out/messaging/` |
| Moved | `SagaRouteConfig` → `adapter/in/kafka/` |
| Moved | All `infrastructure/persistence/` → `adapter/out/persistence/` |
| Moved | All `infrastructure/messaging/` → `adapter/out/messaging/` |
| Moved | All `infrastructure/storage/` → `adapter/out/storage/` |
| Moved | All `infrastructure/client/` → `adapter/out/client/` |
| Moved | All `infrastructure/pdf/` → `adapter/out/pdf/` |
| Deleted | `domain/event/` package (all 4 classes relocated) |
| Logic change | `SagaCommandHandler` implements use-case interfaces; methods renamed to `handle()` |
| New tests | `InvoicePdfGenerationExceptionTest`, `KafkaCommandMapperTest` |

# Fix InvoicePdfGeneratedEvent correlationId Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix `InvoicePdfGeneratedEvent` so `TraceEvent.getCorrelationId()` returns the correct value by removing the shadowing subclass field and wiring the existing constructor parameter to `super()`.

**Architecture:** The subclass currently holds a duplicate `private final String correlationId` field that shadows `TraceEvent.getCorrelationId()`. Remove the field, remove the `this.correlationId = correlationId` assignments, and change the `super()` calls to pass `correlationId` to `TraceEvent`. No caller or test call-site changes needed — the external constructor signature is unchanged.

**Tech Stack:** Java 21, Spring Boot 3.2.5, Jackson, JUnit 5, Maven (`mvn test`)

---

## File Map

| Action | File |
|--------|------|
| Modify | `src/main/java/com/wpanther/invoice/pdf/domain/event/InvoicePdfGeneratedEvent.java` |

---

### Task 1: Add a regression test that proves the current bug

The subclass field `correlationId` shadows `TraceEvent.getCorrelationId()`. Currently `event.getCorrelationId()` returns the subclass field value (which happens to match), but `TraceEvent.getCorrelationId()` (called polymorphically) returns `null` because the field in the parent is never set. Add a test that proves the polymorphic accessor works:

**Files:**
- The test files for this service are `EventPublisherTest.java` and `SagaRouteConfigTest.java`. Find either one to add this test.

- [ ] **Step 1: Add regression test**

Find an appropriate test class (e.g., the existing event test class if present, or `EventPublisherTest.java`) in:
```
src/test/java/com/wpanther/invoice/pdf/
```

Add:

```java
@Test
void getCorrelationId_polymorphicAccessorShouldReturnProvidedValue() {
    // Arrange
    InvoicePdfGeneratedEvent event = new InvoicePdfGeneratedEvent(
            "doc-123", "inv-001", "INV-001",
            "http://localhost/doc.pdf", 12345L, true,
            "corr-abc");

    // Act — call via polymorphic TraceEvent reference
    TraceEvent traceEvent = event;

    // Assert
    assertThat(traceEvent.getCorrelationId()).isEqualTo("corr-abc");
}
```

You may need `import com.wpanther.saga.domain.model.TraceEvent;`.

- [ ] **Step 2: Run it to confirm the bug**

```bash
cd /path/to/invoice-pdf-generation-service
mvn test -Dtest="*" 2>&1 | grep -E "FAIL|getCorrelationId|AssertionError"
```

Expected: the new test fails because `traceEvent.getCorrelationId()` returns `null`.

---

### Task 2: Fix `InvoicePdfGeneratedEvent.java`

**Files:**
- Modify: `src/main/java/com/wpanther/invoice/pdf/domain/event/InvoicePdfGeneratedEvent.java`

- [ ] **Step 1: Remove the duplicate `correlationId` field**

Delete lines 40–41:
```java
@JsonProperty("correlationId")
private final String correlationId;
```

- [ ] **Step 2: Fix the creation constructor `super()` call and remove field assignment**

Find the creation constructor (around line 47). Change the `super()` call from the old 4-arg form to the new 5-arg form that passes `correlationId`, and remove the `this.correlationId = correlationId;` assignment:

```java
// BEFORE
public InvoicePdfGeneratedEvent(
        String documentId,
        String invoiceId,
        String invoiceNumber,
        String documentUrl,
        long fileSize,
        boolean xmlEmbedded,
        String correlationId
) {
    super(null, SOURCE, TRACE_TYPE, null);
    this.documentId = documentId;
    this.invoiceId = invoiceId;
    this.invoiceNumber = invoiceNumber;
    this.documentUrl = documentUrl;
    this.fileSize = fileSize;
    this.xmlEmbedded = xmlEmbedded;
    this.correlationId = correlationId;    // <-- remove this line
}
```

```java
// AFTER
public InvoicePdfGeneratedEvent(
        String documentId,
        String invoiceId,
        String invoiceNumber,
        String documentUrl,
        long fileSize,
        boolean xmlEmbedded,
        String correlationId
) {
    super(null, correlationId, SOURCE, TRACE_TYPE, null);
    this.documentId = documentId;
    this.invoiceId = invoiceId;
    this.invoiceNumber = invoiceNumber;
    this.documentUrl = documentUrl;
    this.fileSize = fileSize;
    this.xmlEmbedded = xmlEmbedded;
}
```

Note: `null` is passed for `sagaId` intentionally — this is a notification event, not saga-scoped.

- [ ] **Step 3: Fix the `@JsonCreator` constructor**

Find the `@JsonCreator` constructor (around line 71). The `correlationId` parameter **already exists** in the parameter list — it was wired to `this.correlationId` which is now removed. Do NOT add a duplicate parameter. Make two changes:

1. Remove the `this.correlationId = correlationId;` assignment from the body.
2. Change the `super()` call to the 9-arg form, passing `null` for `sagaId` and `correlationId` from the parameter:

```java
// BEFORE (super call line, around line 89)
super(eventId, occurredAt, eventType, version, sagaId, source, traceType, context);
// ...
this.correlationId = correlationId;   // <-- remove this

// AFTER (super call)
super(eventId, occurredAt, eventType, version, null, correlationId, source, traceType, context);
// (no this.correlationId assignment)
```

Note: `null` is passed for `sagaId` in the 9-arg super call intentionally — the `sagaId` `@JsonCreator` parameter stays in the parameter list for JSON compatibility but is discarded. This event is a notification event, not saga-scoped.

---

### Task 3: Run tests and commit

- [ ] **Step 1: Run all tests**

```bash
cd /path/to/invoice-pdf-generation-service
mvn test
```

Expected: **BUILD SUCCESS** — all tests pass. The regression test from Task 1 now passes. Existing assertions on `event.getCorrelationId()` in `EventPublisherTest` and `SagaRouteConfigTest` continue to pass because the value now comes from `TraceEvent.getCorrelationId()` instead of the removed subclass field, but the value is identical.

- [ ] **Step 2: Commit**

```bash
git add \
  src/main/java/com/wpanther/invoice/pdf/domain/event/InvoicePdfGeneratedEvent.java \
  src/test/java/  # include whatever test file you added the regression test to
git commit -m "fix: remove shadowing correlationId field from InvoicePdfGeneratedEvent"
```

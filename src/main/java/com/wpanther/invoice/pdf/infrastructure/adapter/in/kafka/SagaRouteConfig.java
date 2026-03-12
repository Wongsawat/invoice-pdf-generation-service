package com.wpanther.invoice.pdf.infrastructure.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.invoice.pdf.application.service.SagaCommandHandler;
import com.wpanther.invoice.pdf.application.usecase.CompensateInvoicePdfUseCase;
import com.wpanther.invoice.pdf.application.usecase.ProcessInvoicePdfUseCase;
import com.wpanther.saga.domain.enums.SagaStep;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Apache Camel routes for saga command and compensation consumers.
 *
 * <p>This route:</p>
 * <ul>
 *   <li>Consumes from: saga.command.invoice-pdf (process commands from orchestrator)</li>
 *   <li>Consumes from: saga.compensation.invoice-pdf (compensation commands from orchestrator)</li>
 *   <li>DLQ: pdf.generation.invoice.dlq</li>
 * </ul>
 *
 * <p>Events are published via outbox pattern (not direct Kafka produce).</p>
 *
 * <p>All Kafka URI parameters are resolved via Camel property placeholders
 * ({@code {{key}}}) so they can be tuned per deployment without recompilation.</p>
 */
@Component
@Slf4j
public class SagaRouteConfig extends RouteBuilder {

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

    @Override
    public void configure() throws Exception {

        // Global error handler: Dead Letter Channel with retries + saga orchestrator notification.
        // onPrepareFailure is invoked once when all retries are exhausted, just before the message
        // is sent to the DLQ.  If the body was already deserialized (failure happened after unmarshal)
        // we publish a FAILURE reply in a new transaction so the orchestrator is not left waiting.
        errorHandler(deadLetterChannel(
                        "kafka:{{app.kafka.topics.dlq}}?brokers={{app.kafka.bootstrap-servers}}")
                        .maximumRedeliveries(3)
                        .redeliveryDelay(1000)
                        .useExponentialBackOff()
                        .backOffMultiplier(2)
                        .maximumRedeliveryDelay(10000)
                        .logExhausted(true)
                        .logStackTrace(true)
                        .onPrepareFailure(exchange -> {
                            Throwable cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
                            Object body = exchange.getIn().getBody();
                            if (body instanceof KafkaProcessInvoicePdfCommand cmd) {
                                log.error("DLQ: notifying orchestrator of retry exhaustion for saga {} invoice {}",
                                        cmd.getSagaId(), cmd.getInvoiceNumber());
                                sagaCommandHandler.publishOrchestrationFailure(commandMapper.toProcess(cmd), cause);
                            } else if (body instanceof KafkaCompensateInvoicePdfCommand cmd) {
                                log.error("DLQ: notifying orchestrator of compensation retry exhaustion for saga {} invoice {}",
                                        cmd.getSagaId(), cmd.getInvoiceId());
                                sagaCommandHandler.publishCompensationOrchestrationFailure(commandMapper.toCompensate(cmd), cause);
                            } else {
                                // Body was never deserialized (e.g., malformed JSON, unknown enum).
                                // Attempt to recover saga coordinates from the raw payload so the
                                // orchestrator is not left waiting indefinitely for a reply.
                                log.error("DLQ: body not deserialized ({}); attempting saga metadata recovery",
                                        body == null ? "null" : body.getClass().getSimpleName());
                                recoverAndNotifyOrchestrator(body, cause);
                            }
                        }));

        // ============================================================
        // CONSUMER ROUTE: saga.command.invoice-pdf (from orchestrator)
        // ============================================================
        from("kafka:{{app.kafka.topics.saga-command-invoice-pdf}}"
                        + "?brokers={{app.kafka.bootstrap-servers}}"
                        + "&groupId={{app.kafka.consumer.command-group-id}}"
                        + "&autoOffsetReset=earliest"
                        + "&autoCommitEnable=false"
                        + "&breakOnFirstError={{app.kafka.consumer.break-on-first-error}}"
                        + "&maxPollRecords={{app.kafka.consumer.max-poll-records}}"
                        + "&consumersCount={{app.kafka.consumer.consumers-count}}")
                        .routeId("saga-command-consumer")
                        .log(LoggingLevel.DEBUG, "Received saga command from Kafka: partition=${header[kafka.PARTITION]}, offset=${header[kafka.OFFSET]}")
                        .unmarshal().json(JsonLibrary.Jackson, KafkaProcessInvoicePdfCommand.class)
                        .process(exchange -> {
                                KafkaProcessInvoicePdfCommand cmd = exchange.getIn().getBody(KafkaProcessInvoicePdfCommand.class);
                                log.info("Processing saga command for saga: {}, invoice: {}",
                                                cmd.getSagaId(), cmd.getInvoiceNumber());
                                processUseCase.handle(commandMapper.toProcess(cmd));
                        })
                        .log("Successfully processed saga command");

        // ============================================================
        // CONSUMER ROUTE: saga.compensation.invoice-pdf (from orchestrator)
        // ============================================================
        from("kafka:{{app.kafka.topics.saga-compensation-invoice-pdf}}"
                        + "?brokers={{app.kafka.bootstrap-servers}}"
                        + "&groupId={{app.kafka.consumer.compensation-group-id}}"
                        + "&autoOffsetReset=earliest"
                        + "&autoCommitEnable=false"
                        + "&breakOnFirstError={{app.kafka.consumer.break-on-first-error}}"
                        + "&maxPollRecords={{app.kafka.consumer.max-poll-records}}"
                        + "&consumersCount={{app.kafka.consumer.consumers-count}}")
                        .routeId("saga-compensation-consumer")
                        .log(LoggingLevel.DEBUG, "Received compensation command from Kafka: partition=${header[kafka.PARTITION]}, offset=${header[kafka.OFFSET]}")
                        .unmarshal().json(JsonLibrary.Jackson, KafkaCompensateInvoicePdfCommand.class)
                        .process(exchange -> {
                                KafkaCompensateInvoicePdfCommand cmd = exchange.getIn().getBody(KafkaCompensateInvoicePdfCommand.class);
                                log.info("Processing compensation for saga: {}, invoice: {}",
                                                cmd.getSagaId(), cmd.getInvoiceId());
                                compensateUseCase.handle(commandMapper.toCompensate(cmd));
                        })
                        .log("Successfully processed compensation command");
    }

    /**
     * Best-effort: parse raw Kafka message body as JSON and extract {@code sagaId},
     * {@code sagaStep}, and {@code correlationId} so the orchestrator can be notified
     * of the failure rather than waiting for a saga timeout.
     *
     * <p>This is only called when Camel's {@code unmarshal()} step failed (i.e. the body
     * is still a raw {@code byte[]} or {@code String}), so we parse leniently via
     * {@link JsonNode} rather than full object deserialization.</p>
     */
    private void recoverAndNotifyOrchestrator(Object body, Throwable cause) {
        if (body == null) {
            log.error("DLQ: null message body — orchestrator must timeout");
            return;
        }
        try {
            byte[] rawBytes = body instanceof byte[] b
                    ? b
                    : body.toString().getBytes(StandardCharsets.UTF_8);
            JsonNode node        = objectMapper.readTree(rawBytes);
            String sagaId        = node.path("sagaId").asText(null);
            String sagaStepStr   = node.path("sagaStep").asText(null);
            String correlationId = node.path("correlationId").asText(null);

            if (sagaId == null || sagaStepStr == null) {
                log.error("DLQ: saga metadata missing in raw message — orchestrator must timeout");
                return;
            }
            // Deserialize SagaStep using the configured ObjectMapper (supports kebab-case codes)
            SagaStep sagaStep = objectMapper.readValue(
                    "\"" + sagaStepStr + "\"", SagaStep.class);
            sagaCommandHandler.publishOrchestrationFailureForUnparsedMessage(
                    sagaId, sagaStep, correlationId, cause);
        } catch (Exception parseEx) {
            log.error("DLQ: cannot parse raw message for saga metadata — orchestrator must timeout", parseEx);
        }
    }
}

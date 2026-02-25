package com.wpanther.invoice.pdf.infrastructure.config;

import com.wpanther.invoice.pdf.application.service.SagaCommandHandler;
import com.wpanther.invoice.pdf.domain.event.CompensateInvoicePdfCommand;
import com.wpanther.invoice.pdf.domain.event.ProcessInvoicePdfCommand;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
 */
@Component
@Slf4j
public class SagaRouteConfig extends RouteBuilder {

    private final SagaCommandHandler sagaCommandHandler;

    @Value("${app.kafka.bootstrap-servers}")
    private String kafkaBrokers;

    @Value("${app.kafka.topics.saga-command-invoice-pdf}")
    private String sagaCommandTopic;

    @Value("${app.kafka.topics.saga-compensation-invoice-pdf}")
    private String sagaCompensationTopic;

    @Value("${app.kafka.topics.dlq:pdf.generation.invoice.dlq}")
    private String dlqTopic;

    @Value("${app.kafka.consumer.group-id}")
    private String consumerGroupId;

    public SagaRouteConfig(SagaCommandHandler sagaCommandHandler) {
        this.sagaCommandHandler = sagaCommandHandler;
    }

    @Override
    public void configure() throws Exception {

        // Global error handler: Dead Letter Channel with retries + saga orchestrator notification.
        // onPrepareFailure is invoked once when all retries are exhausted, just before the message
        // is sent to the DLQ.  If the body was already deserialized (failure happened after unmarshal)
        // we publish a FAILURE reply in a new transaction so the orchestrator is not left waiting.
        errorHandler(deadLetterChannel("kafka:" + dlqTopic + "?brokers=" + kafkaBrokers)
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
                            if (body instanceof ProcessInvoicePdfCommand cmd) {
                                log.error("DLQ: notifying orchestrator of retry exhaustion for saga {} invoice {}",
                                        cmd.getSagaId(), cmd.getInvoiceNumber());
                                sagaCommandHandler.publishOrchestrationFailure(cmd, cause);
                            } else {
                                log.error("DLQ: cannot notify orchestrator — body not deserialized ({})",
                                        body == null ? "null" : body.getClass().getSimpleName());
                            }
                        }));

        // ============================================================
        // CONSUMER ROUTE: saga.command.invoice-pdf (from orchestrator)
        // ============================================================
        from("kafka:" + sagaCommandTopic
                        + "?brokers=" + kafkaBrokers
                        + "&groupId=" + consumerGroupId
                        + "&autoOffsetReset=earliest"
                        + "&autoCommitEnable=false"
                        + "&breakOnFirstError=true"
                        + "&maxPollRecords=100"
                        + "&consumersCount=3")
                        .routeId("saga-command-consumer")
                        .log("Received saga command from Kafka: partition=${header[kafka.PARTITION]}, offset=${header[kafka.OFFSET]}")
                        .unmarshal().json(JsonLibrary.Jackson, ProcessInvoicePdfCommand.class)
                        .process(exchange -> {
                                ProcessInvoicePdfCommand cmd = exchange.getIn().getBody(ProcessInvoicePdfCommand.class);
                                log.info("Processing saga command for saga: {}, invoice: {}",
                                                cmd.getSagaId(), cmd.getInvoiceNumber());
                                sagaCommandHandler.handleProcessCommand(cmd);
                        })
                        .log("Successfully processed saga command");

        // ============================================================
        // CONSUMER ROUTE: saga.compensation.invoice-pdf (from orchestrator)
        // ============================================================
        from("kafka:" + sagaCompensationTopic
                        + "?brokers=" + kafkaBrokers
                        + "&groupId=" + consumerGroupId
                        + "&autoOffsetReset=earliest"
                        + "&autoCommitEnable=false"
                        + "&breakOnFirstError=true"
                        + "&maxPollRecords=100"
                        + "&consumersCount=3")
                        .routeId("saga-compensation-consumer")
                        .log("Received compensation command from Kafka: partition=${header[kafka.PARTITION]}, offset=${header[kafka.OFFSET]}")
                        .unmarshal().json(JsonLibrary.Jackson, CompensateInvoicePdfCommand.class)
                        .process(exchange -> {
                                CompensateInvoicePdfCommand cmd = exchange.getIn().getBody(CompensateInvoicePdfCommand.class);
                                log.info("Processing compensation for saga: {}, invoice: {}",
                                                cmd.getSagaId(), cmd.getInvoiceId());
                                sagaCommandHandler.handleCompensation(cmd);
                        })
                        .log("Successfully processed compensation command");
    }
}

package com.pairforge.api.execution;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnWebApplication
public class ExecutionJobPublisher {
    private final RabbitTemplate rabbit;
    private final ExecutionProperties properties;
    private final MeterRegistry metrics;
    public ExecutionJobPublisher(RabbitTemplate rabbit, ExecutionProperties properties, MeterRegistry metrics) {
        this.rabbit = rabbit; this.properties = properties; this.metrics = metrics;
    }
    /** Null means confirmed/routed. A failure reason never asserts that uncertain messages cannot arrive. */
    public FailureReason publish(UUID id) {
        boolean uncertain = false;
        for (int attempt = 0; attempt < properties.publishAttempts(); attempt++) {
            var headers = new MessageProperties();
            headers.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            headers.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            headers.setMessageId(id.toString());
            var message = new Message(("{\"schemaVersion\":1,\"executionId\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8), headers);
            var correlation = new CorrelationData(UUID.randomUUID().toString());
            try {
                rabbit.send(ExecutionMessaging.EXCHANGE, ExecutionMessaging.JOBS, message, correlation);
                var confirm = correlation.getFuture().get(properties.confirmTimeoutMs(), TimeUnit.MILLISECONDS);
                // Spring completes correlated confirms after the corresponding mandatory return.
                if (confirm.isAck() && correlation.getReturned() == null) return null;
                // AMQP basic.nack has no reason. Spring adds one when it generates a
                // nack locally (e.g. channel loss); delivery may already have happened.
                if (!confirm.isAck() && confirm.getReason() != null && correlation.getReturned() == null)
                    uncertain = true;
            } catch (AmqpConnectException error) {
                // No connection was established on this attempt; retain earlier uncertainty.
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt(); uncertain = true; break;
            } catch (TimeoutException | ExecutionException | RuntimeException error) {
                uncertain = true;
            }
            if (attempt + 1 < properties.publishAttempts()) {
                try { Thread.sleep(properties.retryBackoffMs()); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); uncertain = true; break; }
            }
        }
        var reason = uncertain ? FailureReason.DISPATCH_UNCONFIRMED : FailureReason.DISPATCH_FAILED;
        metrics.counter("pairforge.execution.dispatch.failures", "reason", reason.name()).increment();
        return reason;
    }
}

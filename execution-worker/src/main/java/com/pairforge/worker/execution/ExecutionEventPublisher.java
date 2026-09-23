package com.pairforge.worker.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(ExecutionEventProperties.class)
public class ExecutionEventPublisher {
    private final RabbitTemplate rabbit;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final ExecutionEventProperties properties;
    public ExecutionEventPublisher(RabbitTemplate rabbit, ObjectMapper json, MeterRegistry metrics, ExecutionEventProperties properties) {
        this.rabbit = rabbit; this.json = json; this.metrics = metrics; this.properties = properties;
    }
    /** Notification failure never changes the execution or causes source to run again. */
    public boolean publish(ExecutionEvent event) {
        for (int attempt = 0; attempt < properties.attempts(); attempt++) {
            try {
                var headers = new MessageProperties();
                headers.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                headers.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                headers.setMessageId(event.executionId() + ":" + event.stateRevision());
                var correlation = new CorrelationData(UUID.randomUUID().toString());
                rabbit.send("pairforge.execution", "execution.events", new Message(json.writeValueAsBytes(event), headers), correlation);
                var confirm = correlation.getFuture().get(properties.confirmTimeoutMs(), TimeUnit.MILLISECONDS);
                if (confirm.isAck() && correlation.getReturned() == null) return true;
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); break; }
            catch (Exception error) { /* Bounded retry; only safe identifiers are logged below. */ }
            if (attempt + 1 < properties.attempts()) {
                try { Thread.sleep(properties.retryBackoffMs()); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); break; }
            }
        }
        failed(event.executionId());
        return false;
    }
    public void failed(UUID id) {
        metrics.counter("pairforge.execution.events.publish.failures").increment();
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Execution notification unavailable executionId={}; recover through REST", id);
    }
}

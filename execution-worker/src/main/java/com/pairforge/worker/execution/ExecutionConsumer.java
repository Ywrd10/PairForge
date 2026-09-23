package com.pairforge.worker.execution;

import com.fasterxml.jackson.databind.*;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ShutdownSignalException;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.*;
import org.springframework.amqp.rabbit.listener.*;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.boot.actuate.health.*;
import org.springframework.context.SmartLifecycle;
import org.springframework.util.backoff.FixedBackOff;

/** A stopped-on-failure consumer: only a process restart clears the failure latch. */
public class ExecutionConsumer implements SmartLifecycle, HealthIndicator, AutoCloseable {
    public static final String QUEUE = "execution.jobs";
    private final WorkerProperties properties;
    private final ExecutionProcessor processor;
    private final SimpleMessageListenerContainer container;
    private final ObjectReader reader;
    private final ExecutorService control = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("execution-control").factory());
    private final AtomicBoolean paused = new AtomicBoolean();
    private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("execution-reconciliation").factory());
    private volatile boolean running;
    private volatile boolean ready;
    private volatile boolean closing;

    public ExecutionConsumer(ConnectionFactory connection, ObjectMapper mapper, ExecutionProcessor processor, WorkerProperties properties) {
        this(connection, mapper, processor, properties, new SimpleMessageListenerContainer(connection));
    }
    ExecutionConsumer(ConnectionFactory connection, ObjectMapper mapper, ExecutionProcessor processor,
                      WorkerProperties properties, SimpleMessageListenerContainer container) {
        this.properties = properties; this.processor = processor;
        reader = mapper.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.container = container;
        container.setQueueNames(QUEUE);
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setConcurrentConsumers(1); container.setMaxConcurrentConsumers(1); container.setPrefetchCount(1);
        container.setAutoStartup(false); container.setAutoDeclare(false);
        container.setMissingQueuesFatal(true); container.setDeclarationRetries(0);
        container.setRecoveryBackOff(new FixedBackOff(0, 0));
        container.setDefaultRequeueRejected(false);
        container.setShutdownTimeout(1000); container.setForceCloseChannel(true); container.setForceStop(true);
        container.setMessageListener((ChannelAwareMessageListener) this::receive);
        container.setErrorHandler(error -> halt("LISTENER_FAILURE", error));
        container.setApplicationEventPublisher(event -> {
            if (event instanceof ListenerContainerConsumerFailedEvent failure) halt("CONSUMER_FAILURE", failure.getThrowable());
        });
        container.afterPropertiesSet();
        if (properties.enabled()) connection.addConnectionListener(new ConnectionListener() {
            @Override public void onCreate(Connection ignored) {}
            @Override public void onShutDown(ShutdownSignalException error) { halt("BROKER_CONNECTION_LOST", error); }
            @Override public void onFailed(Exception error) { halt("BROKER_CONNECTION_FAILED", error); }
        });
    }
    private void receive(Message message, Channel channel) {
        if (paused.get() || closing) return;
        try {
            final UUID id;
            try { id = decode(message); }
            catch (Exception error) {
                LoggerFactory.getLogger(getClass()).warn("Rejecting invalid execution message");
                channel.basicReject(message.getMessageProperties().getDeliveryTag(), false);
                return;
            }
            var outcome = processor.process(id);
            if (paused.get() || closing) return; // The durable state can be recovered on redelivery.
            if (outcome == ExecutionProcessor.Outcome.ACK) channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
            else {
                LoggerFactory.getLogger(getClass()).warn("Rejecting missing execution executionId={}", id);
                channel.basicReject(message.getMessageProperties().getDeliveryTag(), false);
            }
        } catch (Exception error) {
            // No ack/nack of a valid failed job. Closing the channel returns it to the queue.
            halt("PROCESSING_FAILURE", error);
        }
    }
    UUID decode(Message message) throws java.io.IOException {
        if (message.getBody().length > 1024 || !MessageProperties.CONTENT_TYPE_JSON.equals(message.getMessageProperties().getContentType()))
            throw new IllegalArgumentException("Invalid message envelope");
        JsonNode body = reader.readTree(message.getBody());
        if (body == null || !body.isObject() || body.size() != 2 || !body.path("schemaVersion").isInt()
                || body.path("schemaVersion").intValue() != 1 || !body.path("executionId").isTextual())
            throw new IllegalArgumentException("Invalid job schema");
        String value = body.get("executionId").asText();
        UUID id = UUID.fromString(value);
        if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException("Invalid execution ID");
        return id;
    }
    private void halt(String reason, Throwable error) {
        synchronized (control) {
            if (closing || !properties.enabled() || !paused.compareAndSet(false, true)) return;
            ready = false;
            LoggerFactory.getLogger(getClass()).error("Worker consumption paused reason={} type={}; operator restart required",
                    reason, error == null ? "none" : error.getClass().getSimpleName());
            control.execute(container::stop);
        }
    }
    @Override public synchronized void start() {
        if (!properties.enabled() || running || paused.get() || closing) return;
        running = true;
        try {
            processor.recover();
            if (!paused.get()) {
                container.start(); ready = !paused.get();
                maintenance.scheduleWithFixedDelay(() -> {
                    try { processor.reconcile(); }
                    catch (Exception error) { halt("RECONCILIATION_FAILED", error); }
                }, processor.reconciliationMs(), processor.reconciliationMs(), TimeUnit.MILLISECONDS);
            }
        } catch (RuntimeException error) { halt("STARTUP_RECOVERY_FAILED", error); }
    }
    @Override public synchronized void stop() {
        if (closing) return;
        synchronized (control) {
            closing = true; ready = false;
            control.shutdown();
        }
        maintenance.shutdownNow();
        try {
            // A failure may already be closing channels on the control thread. Do not
            // interrupt that close or let it outlive Spring's connection factory.
            if (!control.awaitTermination(10, TimeUnit.SECONDS))
                throw new IllegalStateException("Worker consumer shutdown did not finish within 10 seconds");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for worker consumer shutdown", error);
        }
        finally {
            try { container.stop(); }
            finally {
                try { if (processor != null) processor.close(); }
                finally { control.shutdownNow(); running = false; }
            }
        }
    }
    @Override public boolean isRunning() { return running; }
    @Override public boolean isAutoStartup() { return properties.enabled(); }
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
    @Override public Health health() {
        return !properties.enabled() || ready && !paused.get() && container.isRunning() ? Health.up().build() : Health.down().build();
    }
    @Override public void close() { stop(); }
}

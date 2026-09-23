package com.pairforge.api.execution;

import com.fasterxml.jackson.databind.*;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnWebApplication
public class ExecutionEventConsumer implements ChannelAwareMessageListener {
    private final ObjectReader reader;
    private final ExecutionRepository executions;
    private final SimpMessagingTemplate sockets;
    private final MeterRegistry metrics;
    // One consumer. Bounded optimization only; PostgreSQL and browser revisions remain authoritative.
    private final Map<UUID, Long> delivered = new LinkedHashMap<>();
    public ExecutionEventConsumer(ObjectMapper json, ExecutionRepository executions, SimpMessagingTemplate sockets, MeterRegistry metrics) {
        reader = json.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.executions = executions; this.sockets = sockets; this.metrics = metrics;
    }
    ExecutionEvent decode(Message message) throws Exception {
        if (message.getBody().length > 1024 || !MessageProperties.CONTENT_TYPE_JSON.equals(message.getMessageProperties().getContentType()))
            throw new IllegalArgumentException("Invalid event envelope");
        JsonNode body = reader.readTree(message.getBody());
        if (body == null || !body.isObject() || body.size() != 5 || !body.path("schemaVersion").isInt()
                || !body.path("executionId").isTextual() || !body.path("roomId").isTextual()
                || !body.path("status").isTextual() || !body.path("stateRevision").isIntegralNumber()
                || !body.path("stateRevision").canConvertToLong()) throw new IllegalArgumentException("Invalid event schema");
        UUID id = UUID.fromString(body.get("executionId").asText()), room = UUID.fromString(body.get("roomId").asText());
        if (!id.toString().equals(body.get("executionId").asText()) || !room.toString().equals(body.get("roomId").asText()))
            throw new IllegalArgumentException("Invalid event ID");
        return new ExecutionEvent(body.get("schemaVersion").intValue(), id, room, body.get("status").textValue(), body.get("stateRevision").longValue());
    }
    @Override public void onMessage(Message message, Channel channel) throws Exception {
        long tag = message.getMessageProperties().getDeliveryTag();
        final ExecutionEvent event;
        try { event = decode(message); }
        catch (Exception malformed) { rejected("invalid"); channel.basicReject(tag, false); return; }
        boolean handled = false;
        for (int attempt = 0; attempt < 3; attempt++) {
            try { broadcast(event); handled = true; break; }
            catch (RuntimeException failure) {
                if (attempt < 2) {
                    try { Thread.sleep(100L * (attempt + 1)); }
                    catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw stopped; }
                }
            }
        }
        // Local handoff, not receipt by a browser. Exhaustion is recovered through REST, never infinite requeue.
        if (handled) channel.basicAck(tag, false);
        else { rejected("unavailable"); channel.basicReject(tag, false); }
    }
    synchronized void broadcast(ExecutionEvent event) {
        var current = executions.notification(event.executionId()).orElse(null);
        if (current == null || !current.getRoomId().equals(event.roomId()) || current.getStateRevision() < event.stateRevision()
                || current.getStateRevision() == event.stateRevision() && !current.getStatus().name().equals(event.status())) {
            rejected("mismatch"); return;
        }
        long revision = current.getStateRevision();
        if (delivered.getOrDefault(event.executionId(), -1L) >= revision) return;
        sockets.convertAndSend("/topic/rooms/" + current.getRoomId() + "/executions",
                new ExecutionEvent(1, current.getId(), current.getRoomId(), current.getStatus().name(), revision));
        delivered.put(event.executionId(), revision);
        if (delivered.size() > 1024) delivered.remove(delivered.keySet().iterator().next());
    }
    private void rejected(String reason) {
        metrics.counter("pairforge.execution.events.consume.failures", "reason", reason).increment();
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Execution notification discarded reason={}; recover through REST", reason);
    }
}

package com.pairforge.api.collaboration;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class CollaborationSessions {
    public static class State {
        final WebSocketSession socket;
        final String ip;
        final Instant deadline;
        volatile UUID user;
        volatile Instant expires;
        UUID room;
        long sequence;
        UUID lastUpdateId;
        long window = System.nanoTime();
        int messages;
        int frames;
        final Map<String, String> subscriptions = new HashMap<>();
        State(WebSocketSession socket, Instant deadline) {
            this.socket = socket; this.deadline = deadline;
            ip = socket.getRemoteAddress() == null ? "unknown" : socket.getRemoteAddress().getAddress().getHostAddress();
        }
    }
    private final Map<String, State> sessions = new ConcurrentHashMap<>();
    private final CollaborationProperties properties;
    private final Clock clock;
    public CollaborationSessions(CollaborationProperties properties, Clock clock) { this.properties = properties; this.clock = clock; }
    public synchronized boolean open(WebSocketSession socket) {
        var state = new State(socket, clock.instant().plusMillis(properties.connectTimeoutMs()));
        if (sessions.size() >= properties.maxConnections()
                || sessions.values().stream().filter(s -> s.ip.equals(state.ip)).count() >= properties.maxConnectionsPerIp()) return false;
        sessions.put(socket.getId(), state);
        return true;
    }
    public State require(String id) {
        State state = sessions.get(id);
        if (state == null) throw new IllegalArgumentException("Connection unavailable");
        Instant deadline = state.expires == null ? state.deadline : state.expires;
        if (!clock.instant().isBefore(deadline)) { close(id); throw new IllegalArgumentException("Connection expired"); }
        return state;
    }
    public synchronized void authenticate(State state, UUID user, Instant expires) {
        if (state.user != null || sessions.values().stream().filter(s -> user.equals(s.user)).count() >= properties.maxConnectionsPerUser()) {
            throw new IllegalArgumentException("Connection admission denied");
        }
        state.user = user; state.expires = expires;
    }
    public void charge(String id, boolean frame) {
        State state = require(id);
        synchronized (state) {
            long now = System.nanoTime();
            if (now - state.window >= 1_000_000_000L) { state.window = now; state.messages = 0; state.frames = 0; }
            int count = frame ? ++state.frames : ++state.messages;
            if (count > properties.messagesPerSecond() * (frame ? 10L : 1L)) throw new IllegalArgumentException("Message rate exceeded");
        }
    }
    public void remove(String id) { sessions.remove(id); }
    public void close(String id) {
        State state = sessions.remove(id);
        if (state != null) {
            try { state.socket.close(CloseStatus.POLICY_VIOLATION); }
            catch (IOException ignored) { /* Connection already lost; state is removed regardless. */ }
        }
    }
    @Scheduled(fixedDelay = 250, scheduler = "collaborationScheduler")
    public void expire() {
        sessions.forEach((id, state) -> {
            Instant deadline = state.expires == null ? state.deadline : state.expires;
            if (!clock.instant().isBefore(deadline)) close(id);
        });
    }
}

package com.pairforge.api.collaboration;

import java.net.InetSocketAddress;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CollaborationLimitsTest {
    static CollaborationProperties limits() { return new CollaborationProperties(86400, 65536, 400000, 10, 2, 1, 1, 5000, 5000, 1048576); }
    WebSocketSession socket(String ip) {
        var socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(UUID.randomUUID().toString());
        when(socket.getRemoteAddress()).thenReturn(new InetSocketAddress(ip, 1234));
        return socket;
    }
    @Test void ipAndGlobalConnectionCapsReleaseOnDisconnect() {
        var sessions = new CollaborationSessions(limits(), Clock.systemUTC());
        var first = socket("127.0.0.1");
        assertThat(sessions.open(first)).isTrue(); assertThat(sessions.open(socket("127.0.0.1"))).isFalse();
        assertThat(sessions.open(socket("127.0.0.2"))).isTrue(); assertThat(sessions.open(socket("127.0.0.3"))).isFalse();
        sessions.remove(first.getId()); assertThat(sessions.open(socket("127.0.0.3"))).isTrue();
    }
    @Test void deadlinesAndAuthenticatedExpiryCloseIdleConnections() throws Exception {
        var clock = mock(Clock.class); var now = Instant.parse("2026-09-16T00:00:00Z"); when(clock.instant()).thenReturn(now);
        var sessions = new CollaborationSessions(limits(), clock); var first = socket("127.0.0.1"); sessions.open(first);
        when(clock.instant()).thenReturn(now.plusSeconds(6)); sessions.expire(); verify(first).close(CloseStatus.POLICY_VIOLATION);
        var second = socket("127.0.0.2"); sessions.open(second);
        sessions.authenticate(sessions.require(second.getId()), UUID.randomUUID(), now.plusSeconds(7));
        when(clock.instant()).thenReturn(now.plusSeconds(7)); sessions.expire(); verify(second).close(CloseStatus.POLICY_VIOLATION);
    }
    @Test void springSendBufferTerminatesASlowClientInsteadOfGrowingWithoutLimit() throws Exception {
        var blocked = socket("127.0.0.1"); when(blocked.isOpen()).thenReturn(true);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Send was not released"); return null; })
                .when(blocked).sendMessage(any());
        var bounded = new ConcurrentWebSocketSessionDecorator(blocked, limits().sendTimeoutMs(), limits().sendBufferBytes());
        try (var thread = Executors.newSingleThreadExecutor()) {
            var sender = thread.submit(() -> { bounded.sendMessage(new TextMessage("first")); return null; });
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> bounded.sendMessage(new TextMessage("x".repeat(limits().sendBufferBytes() + 1))))
                        .isInstanceOf(SessionLimitExceededException.class);
            } finally { release.countDown(); }
            sender.get(2, TimeUnit.SECONDS);
        }
    }
}

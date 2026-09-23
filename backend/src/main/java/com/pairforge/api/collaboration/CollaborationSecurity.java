package com.pairforge.api.collaboration;

import com.pairforge.api.room.RoomService;
import com.pairforge.api.user.UserRepository;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class CollaborationSecurity implements ChannelInterceptor {
    private static final Pattern TOPIC = Pattern.compile("/topic/rooms/([0-9a-f-]{36})/(document|executions)");
    private static final Pattern SEND = Pattern.compile("/app/rooms/([0-9a-f-]{36})/(snapshot|update)");
    private final CollaborationSessions sessions;
    private final JwtDecoder decoder;
    private final UserRepository users;
    private final RoomService rooms;
    public CollaborationSecurity(CollaborationSessions sessions, JwtDecoder decoder, UserRepository users, RoomService rooms) {
        this.sessions = sessions; this.decoder = decoder; this.users = users; this.rooms = rooms;
    }
    @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
        var headers = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (headers == null) throw new IllegalArgumentException("STOMP required");
        if (headers.getCommand() == StompCommand.DISCONNECT) return message;
        String id = headers.getSessionId();
        try {
            sessions.charge(id, false);
            var state = sessions.require(id);
            synchronized (state) {
                if (headers.getCommand() == StompCommand.CONNECT || headers.getCommand() == StompCommand.STOMP) {
                    var values = headers.getNativeHeader("Authorization");
                    if (values == null || values.size() != 1 || !values.get(0).startsWith("Bearer ")) throw new IllegalArgumentException();
                    var jwt = decoder.decode(values.get(0).substring(7));
                    var user = UUID.fromString(jwt.getSubject());
                    if (!users.existsById(user)) throw new IllegalArgumentException();
                    sessions.authenticate(state, user, jwt.getExpiresAt());
                    headers.setUser(new JwtAuthenticationToken(jwt));
                    headers.removeNativeHeader("Authorization");
                } else {
                    if (state.user == null) throw new IllegalArgumentException();
                    if (headers.getCommand() == null) return message; // Heartbeats never touch document TTL.
                    String destination = headers.getDestination();
                    if (headers.getCommand() == StompCommand.SUBSCRIBE) {
                        String subscription = headers.getSubscriptionId();
                        if (subscription == null || subscription.length() > 64 || state.subscriptions.size() >= 3
                                || state.subscriptions.containsKey(subscription) || state.subscriptions.containsValue(destination)) throw new IllegalArgumentException();
                        if (!"/user/queue/collaboration".equals(destination)) authorize(state, destination, TOPIC);
                        state.subscriptions.put(subscription, destination);
                    } else if (headers.getCommand() == StompCommand.SEND) {
                        authorize(state, destination, SEND);
                        if (!state.subscriptions.containsValue("/topic/rooms/" + state.room + "/document")
                                || !state.subscriptions.containsValue("/user/queue/collaboration")) throw new IllegalArgumentException();
                    } else if (headers.getCommand() == StompCommand.UNSUBSCRIBE) {
                        state.subscriptions.remove(headers.getSubscriptionId());
                    } else throw new IllegalArgumentException();
                }
            }
            return message;
        } catch (RuntimeException failure) {
            sessions.close(id);
            // Never include a frame, token, source, or dependency exception in a STOMP error.
            throw new IllegalArgumentException("Collaboration access denied");
        }
    }
    private void authorize(CollaborationSessions.State state, String destination, Pattern pattern) {
        var match = pattern.matcher(destination == null ? "" : destination);
        if (!match.matches()) throw new IllegalArgumentException();
        UUID room = UUID.fromString(match.group(1));
        if (!room.toString().equals(match.group(1)) || state.room != null && !state.room.equals(room)) throw new IllegalArgumentException();
        rooms.get(state.user, room); // PostgreSQL membership/user authority; dependency loss fails closed.
        state.room = room;
    }
}

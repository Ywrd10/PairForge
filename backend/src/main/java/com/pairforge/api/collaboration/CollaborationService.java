package com.pairforge.api.collaboration;

import com.pairforge.api.collaboration.DocumentMessages.*;
import com.pairforge.api.room.RoomService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class CollaborationService {
    private final DocumentRepository documents;
    private final CollaborationSessions sessions;
    private final CollaborationProperties properties;
    private final RoomService rooms;
    private final SimpMessagingTemplate messages;
    // Bounded stripes serialize commit + publication (including reset/snapshot) on this single API.
    private final Object[] locks = new Object[64];
    public CollaborationService(DocumentRepository documents, CollaborationSessions sessions,
            CollaborationProperties properties, RoomService rooms, SimpMessagingTemplate messages) {
        this.documents = documents; this.sessions = sessions; this.properties = properties; this.rooms = rooms; this.messages = messages;
        java.util.Arrays.setAll(locks, index -> new Object());
    }
    public Event snapshot(UUID room, String session) {
        var state = sessions.require(session);
        synchronized (lock(room)) {
            sessions.require(session);
            var metadata = rooms.get(state.user, room);
            sessions.require(session);
            var result = documents.snapshot(room, metadata.language());
            if (result.created()) broadcast(new Event("DOCUMENT_RESET", room, result.document(), null, null));
            return new Event("SNAPSHOT", room, result.document(), null, result.created() ? "DOCUMENT_RESET" : null);
        }
    }
    public Event update(UUID room, String session, Update update) {
        var state = sessions.require(session);
        validate(update);
        synchronized (state) {
            if (update.sequence() <= state.sequence || update.clientUpdateId().equals(state.lastUpdateId))
                return new Event("REJECTED", room, null, update.clientUpdateId(), "DUPLICATE_UPDATE");
            synchronized (lock(room)) {
                sessions.require(session);
                var metadata = rooms.get(state.user, room);
                sessions.require(session);
                var result = documents.update(room, update);
                if (!result.outcome().equals("OK")) {
                    var current = documents.snapshot(room, metadata.language());
                    if (current.created()) broadcast(new Event("DOCUMENT_RESET", room, current.document(), null, null));
                    return new Event("DOCUMENT_RESET", room, current.document(), update.clientUpdateId(), "STALE_GENERATION");
                }
                state.sequence = update.sequence();
                state.lastUpdateId = update.clientUpdateId();
                var accepted = new Event("UPDATED", room, result.document(), update.clientUpdateId(), null);
                broadcast(accepted);
                return accepted;
            }
        }
    }
    private void validate(Update update) {
        if (update.generationId() == null || update.clientUpdateId() == null || update.sequence() < 1
                || update.sequence() > 9007199254740991L || update.language() == null || update.content() == null
                || !StandardCharsets.UTF_8.newEncoder().canEncode(update.content()) || update.content().indexOf('\0') >= 0
                || update.content().getBytes(StandardCharsets.UTF_8).length > properties.sourceBytes()) {
            throw new IllegalArgumentException("Invalid update");
        }
    }
    private Object lock(UUID room) { return locks[Math.floorMod(room.hashCode(), locks.length)]; }
    private void broadcast(Event event) { messages.convertAndSend("/topic/rooms/" + event.roomId() + "/document", event); }
}

package com.pairforge.api.room;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class RoomMemberId implements Serializable {
    @Column(name = "room_id", nullable = false, updatable = false)
    private UUID roomId;
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    protected RoomMemberId() {}

    public RoomMemberId(UUID roomId, UUID userId) {
        this.roomId = Objects.requireNonNull(roomId, "roomId");
        this.userId = Objects.requireNonNull(userId, "userId");
    }

    public UUID getRoomId() { return roomId; }
    public UUID getUserId() { return userId; }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof RoomMemberId that
                && Objects.equals(roomId, that.roomId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() { return Objects.hash(roomId, userId); }
}

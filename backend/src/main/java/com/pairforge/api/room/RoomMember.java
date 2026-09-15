package com.pairforge.api.room;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.EmbeddedId;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "room_members")
public class RoomMember {
    @EmbeddedId
    private RoomMemberId id;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    protected RoomMember() {}

    public RoomMember(UUID roomId, UUID userId) {
        this.id = new RoomMemberId(roomId, userId);
        this.joinedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public RoomMemberId getId() { return id; }
    public Instant getJoinedAt() { return joinedAt; }
}

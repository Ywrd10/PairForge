package com.pairforge.api.room;

import com.pairforge.api.common.Language;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Id;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "rooms")
public class Room {
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Language language;

    @Column(name = "invitation_token_hash", nullable = false, length = 64)
    private String invitationTokenHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Room() {}

    public Room(UUID ownerId, String name, Language language, String invitationTokenHash) {
        this.id = UUID.randomUUID();
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.name = Objects.requireNonNull(name, "name");
        this.language = Objects.requireNonNull(language, "language");
        this.invitationTokenHash = Objects.requireNonNull(invitationTokenHash, "invitationTokenHash");
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.updatedAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getOwnerId() { return ownerId; }
    public String getName() { return name; }
    public Language getLanguage() { return language; }
    public String getInvitationTokenHash() { return invitationTokenHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

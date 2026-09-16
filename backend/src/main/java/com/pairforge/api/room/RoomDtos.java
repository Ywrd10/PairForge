package com.pairforge.api.room;

import com.pairforge.api.common.Language;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class RoomDtos {
    private RoomDtos() {}

    public record CreateRequest(@NotBlank @Size(max = 120) String name, @NotNull Language language) {
        @AssertTrue(message = "Name must contain valid Unicode without NUL")
        public boolean isNameEncodingValid() {
            return name == null || name.indexOf('\0') < 0 && StandardCharsets.UTF_8.newEncoder().canEncode(name);
        }
    }

    public record JoinRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String invitationToken) {
        @Override public String toString() { return "JoinRequest[redacted]"; }
    }

    public record RoomResponse(UUID id, UUID ownerId, String name, Language language,
                               Instant createdAt, Instant updatedAt) {
        static RoomResponse from(Room room) {
            return new RoomResponse(room.getId(), room.getOwnerId(), room.getName(), room.getLanguage(),
                    room.getCreatedAt(), room.getUpdatedAt());
        }
    }

    public record CreatedRoom(RoomResponse room, String invitationToken) {
        @Override public String toString() { return "CreatedRoom[redacted]"; }
    }

    public record RoomPage(List<RoomResponse> items, int page, int size, boolean hasNext) {}
}

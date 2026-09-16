package com.pairforge.api.room;

import com.pairforge.api.room.RoomDtos.*;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rooms")
public class RoomController {
    private final RoomService rooms;
    public RoomController(RoomService rooms) { this.rooms = rooms; }

    @PostMapping
    ResponseEntity<CreatedRoom> create(@AuthenticationPrincipal Jwt token, @Valid @RequestBody CreateRequest request) {
        var created = rooms.create(UUID.fromString(token.getSubject()), request);
        return ResponseEntity.created(URI.create("/api/rooms/" + created.room().id())).body(created);
    }

    @GetMapping
    RoomPage list(@AuthenticationPrincipal Jwt token, @RequestParam(defaultValue = "0") int page,
                  @RequestParam(defaultValue = "20") int size) {
        return rooms.list(UUID.fromString(token.getSubject()), page, size);
    }

    @GetMapping("/{roomId}")
    RoomResponse get(@AuthenticationPrincipal Jwt token, @PathVariable UUID roomId) {
        return rooms.get(UUID.fromString(token.getSubject()), roomId);
    }

    @PostMapping("/{roomId}/join")
    RoomResponse join(@AuthenticationPrincipal Jwt token, @PathVariable UUID roomId,
                      @Valid @RequestBody JoinRequest request) {
        return rooms.join(UUID.fromString(token.getSubject()), roomId, request);
    }
}

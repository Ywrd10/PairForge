package com.pairforge.api.room;

import com.pairforge.api.common.ApiException;
import com.pairforge.api.room.RoomDtos.*;
import com.pairforge.api.user.UserRepository;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoomService {
    private final RoomRepository rooms;
    private final RoomMemberRepository members;
    private final UserRepository users;
    private final InvitationTokenService invitations;

    public RoomService(RoomRepository rooms, RoomMemberRepository members, UserRepository users,
                       InvitationTokenService invitations) {
        this.rooms = rooms;
        this.members = members;
        this.users = users;
        this.invitations = invitations;
    }

    @Transactional
    public CreatedRoom create(UUID userId, CreateRequest request) {
        requireUser(userId);
        String token = invitations.generate();
        var room = rooms.saveAndFlush(new Room(userId, request.name(), request.language(), invitations.hash(token)));
        members.saveAndFlush(new RoomMember(room.getId(), userId));
        return new CreatedRoom(RoomResponse.from(room), token);
    }

    @Transactional(readOnly = true)
    public RoomPage list(UUID userId, int page, int size) {
        requireUser(userId);
        // Hibernate offsets are ints; reject overflow instead of turning it into a 500.
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new ApiException(400, "INVALID_INPUT", "Invalid room pagination");
        }
        var result = rooms.findRoomsForMember(userId, PageRequest.of(page, size));
        return new RoomPage(result.getContent().stream().map(RoomResponse::from).toList(), page, size, result.hasNext());
    }

    @Transactional(readOnly = true)
    public RoomResponse get(UUID userId, UUID roomId) {
        requireUser(userId);
        if (!members.existsById(new RoomMemberId(roomId, userId))) throw inaccessible();
        return RoomResponse.from(rooms.findById(roomId).orElseThrow(RoomService::inaccessible));
    }

    @Transactional
    public RoomResponse join(UUID userId, UUID roomId, JoinRequest request) {
        requireUser(userId);
        var room = rooms.findById(roomId).orElseThrow(RoomService::inaccessible);
        if (!invitations.matches(request.invitationToken(), room.getInvitationTokenHash())) throw inaccessible();
        // PostgreSQL arbitrates concurrent duplicate joins without aborting the transaction.
        members.insertIfAbsent(roomId, userId);
        return RoomResponse.from(room);
    }

    private void requireUser(UUID userId) {
        if (!users.existsById(userId)) {
            throw new ApiException(401, "UNAUTHORIZED", "Authentication is required or credentials are invalid");
        }
    }

    private static ApiException inaccessible() {
        return new ApiException(404, "ROOM_NOT_FOUND", "Room is unavailable");
    }
}

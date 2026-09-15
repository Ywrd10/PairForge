package com.pairforge.api.room;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomMemberRepository extends JpaRepository<RoomMember, RoomMemberId> {
    long countByIdRoomId(UUID roomId);
}

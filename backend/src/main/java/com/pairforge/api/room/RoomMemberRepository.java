package com.pairforge.api.room;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomMemberRepository extends JpaRepository<RoomMember, RoomMemberId> {
    long countByIdRoomId(UUID roomId);

    @Modifying
    @Query(value = """
            INSERT INTO room_members (room_id, user_id, joined_at) VALUES (:roomId, :userId, now())
            ON CONFLICT (room_id, user_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("roomId") UUID roomId, @Param("userId") UUID userId);
}

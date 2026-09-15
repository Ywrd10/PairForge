package com.pairforge.api.room;

import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomRepository extends JpaRepository<Room, UUID> {
    @Query("""
            select r from Room r where r.id in
                (select m.id.roomId from RoomMember m where m.id.userId = :userId)
            order by r.createdAt desc, r.id desc
            """)
    Slice<Room> findRoomsForMember(@Param("userId") UUID userId, Pageable pageable);
}

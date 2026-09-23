package com.pairforge.api.execution;

import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Collection;

public interface ExecutionRepository extends JpaRepository<Execution, UUID> {
    interface Notification {
        UUID getId(); UUID getRoomId(); ExecutionStatus getStatus(); long getStateRevision();
    }
    @Query("select e.id as id, e.roomId as roomId, e.status as status, e.stateRevision as stateRevision from Execution e where e.id = :id")
    java.util.Optional<Notification> notification(@Param("id") UUID id);
    Slice<Execution> findByRoomIdOrderByCreatedAtDescIdDesc(UUID roomId, Pageable pageable);

    long countByStatusIn(Collection<ExecutionStatus> statuses);

    @Query("""
            select new com.pairforge.api.execution.ExecutionDtos$Summary(e.id, e.roomId, e.submittedBy,
                e.language, e.status, e.createdAt, e.completedAt, e.stateRevision, e.failureReason)
            from Execution e where e.roomId = :room order by e.createdAt desc, e.id desc
            """)
    Slice<ExecutionDtos.Summary> history(@Param("room") UUID room, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Execution e set e.status = com.pairforge.api.execution.ExecutionStatus.FAILED,
                e.failureReason = :reason, e.completedAt = :completed, e.stateRevision = e.stateRevision + 1
            where e.id = :id and e.status = com.pairforge.api.execution.ExecutionStatus.QUEUED
            """)
    int failQueued(@Param("id") UUID id, @Param("reason") FailureReason reason, @Param("completed") Instant completed);
}

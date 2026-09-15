package com.pairforge.api.execution;

import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionRepository extends JpaRepository<Execution, UUID> {
    Slice<Execution> findByRoomIdOrderByCreatedAtDescIdDesc(UUID roomId, Pageable pageable);
}

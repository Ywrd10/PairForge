package com.pairforge.api.execution;

import java.util.Set;
import java.util.UUID;

/** Versioned, bounded notification; PostgreSQL retains source and output. */
public record ExecutionEvent(int schemaVersion, UUID executionId, UUID roomId, String status, long stateRevision) {
    public ExecutionEvent {
        if (schemaVersion != 1 || executionId == null || roomId == null || status == null
                || !Set.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "TIMED_OUT").contains(status)
                || stateRevision < 0 || stateRevision > 9007199254740991L)
            throw new IllegalArgumentException("Invalid execution event");
    }
}

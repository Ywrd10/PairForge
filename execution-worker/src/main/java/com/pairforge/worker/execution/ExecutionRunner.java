package com.pairforge.worker.execution;

import java.util.UUID;

/** The worker's lifecycle boundary for isolated execution and resource cleanup. */
public interface ExecutionRunner {
    /** Verify prerequisites and clean predecessor resources before consuming jobs. */
    default void initialize() throws Exception {}
    /** Reconcile orphaned resources and enforce deadlines independently of message processing. */
    default void reconcile() throws Exception {}
    default long reconciliationMs() { return 2000; }
    /** Return only after activity has ended and resources have been cleaned up. Never run source on the host. */
    ExecutionResult run(ExecutionRepository.Job job) throws Exception;
    /** Idempotently stop and clean all activity for this ID, including resources left by a stopped predecessor.
     * Returning normally certifies that activity has stopped; otherwise throw. */
    void stop(UUID executionId) throws Exception;
}

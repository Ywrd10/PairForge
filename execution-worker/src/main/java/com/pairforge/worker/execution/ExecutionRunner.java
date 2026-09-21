package com.pairforge.worker.execution;

import java.util.UUID;

/** No runtime implementation ships in M9. Tests supply a fake; M10 supplies the sandbox. */
public interface ExecutionRunner {
    /** Return only after activity has ended and resources have been cleaned up. Never run source on the host. */
    ExecutionResult run(ExecutionRepository.Job job) throws Exception;
    /** Idempotently stop and clean all activity for this ID, including resources left by a stopped predecessor.
     * Returning normally certifies that activity has stopped; otherwise throw. */
    void stop(UUID executionId) throws Exception;
}

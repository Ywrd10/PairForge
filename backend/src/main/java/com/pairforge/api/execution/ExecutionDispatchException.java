package com.pairforge.api.execution;

import java.util.UUID;

public class ExecutionDispatchException extends RuntimeException {
    private final UUID executionId;
    private final ExecutionStatus status;
    private final FailureReason failureReason;
    public ExecutionDispatchException(UUID executionId, ExecutionStatus status, FailureReason failureReason) {
        super(status == null ? "Submission outcome is unknown; inspect this execution before resubmitting"
                : "Execution dispatch failed; inspect this execution before resubmitting");
        this.executionId = executionId; this.status = status; this.failureReason = failureReason;
    }
    public UUID executionId() { return executionId; }
    public ExecutionStatus status() { return status; }
    public FailureReason failureReason() { return failureReason; }
}

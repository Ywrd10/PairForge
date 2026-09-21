package com.pairforge.worker.execution;

import java.nio.charset.StandardCharsets;

public record ExecutionResult(Status status, String stdout, String stderr, Integer exitCode,
        Long durationMs, FailureReason failureReason, boolean outputTruncated) {
    public enum Status { SUCCEEDED, FAILED, TIMED_OUT }
    public enum FailureReason { COMPILATION_ERROR, RUNTIME_ERROR, MEMORY_LIMIT, OUTPUT_LIMIT, INFRASTRUCTURE_INTERRUPTION }
    public ExecutionResult {
        if (status == null || !text(stdout) || !text(stderr)
                || (long) stdout.getBytes(StandardCharsets.UTF_8).length + stderr.getBytes(StandardCharsets.UTF_8).length > 65536
                || durationMs != null && durationMs < 0
                || status == Status.SUCCEEDED && failureReason != null
                || status == Status.FAILED && failureReason == null)
            throw new IllegalArgumentException("Invalid or oversized execution result");
    }
    private static boolean text(String value) {
        return value != null && value.indexOf('\0') < 0 && StandardCharsets.UTF_8.newEncoder().canEncode(value);
    }
    public static ExecutionResult interrupted() {
        return new ExecutionResult(Status.FAILED, "", "", null, null, FailureReason.INFRASTRUCTURE_INTERRUPTION, false);
    }
    public static ExecutionResult timedOut() {
        return new ExecutionResult(Status.TIMED_OUT, "", "", null, null, null, false);
    }
}

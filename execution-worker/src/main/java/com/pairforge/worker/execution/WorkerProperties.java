package com.pairforge.worker.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("pairforge.worker")
public record WorkerProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("false") boolean previousWorkerStopped,
        @DefaultValue("30000") long deadlineMs,
        @DefaultValue("2000") long cleanupTimeoutMs,
        @DefaultValue("100") long retryBackoffMs) {
    public WorkerProperties {
        if (deadlineMs < 100 || deadlineMs > 300000 || cleanupTimeoutMs < 100 || cleanupTimeoutMs > 10000
                || retryBackoffMs < 0 || retryBackoffMs > 1000)
            throw new IllegalArgumentException("Worker time limits are outside supported bounds");
    }
}

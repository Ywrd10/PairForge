package com.pairforge.worker.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("pairforge.execution-events")
public record ExecutionEventProperties(@DefaultValue("2") int attempts, @DefaultValue("500") long confirmTimeoutMs,
                                       @DefaultValue("100") long retryBackoffMs) {
    public ExecutionEventProperties {
        if (attempts < 1 || attempts > 3 || confirmTimeoutMs < 50 || confirmTimeoutMs > 2000
                || retryBackoffMs < 0 || retryBackoffMs > 500)
            throw new IllegalArgumentException("Invalid execution event limits");
    }
}

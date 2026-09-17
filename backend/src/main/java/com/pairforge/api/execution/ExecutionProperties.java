package com.pairforge.api.execution;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("pairforge.execution")
public record ExecutionProperties(
        @DefaultValue("10") @Min(1) int userLimit,
        @DefaultValue("60") @Min(1) int windowSeconds,
        @DefaultValue("100") @Min(1) int maxOutstanding,
        @DefaultValue("100") @Min(1) @Max(2000) int admissionWaitMs,
        @DefaultValue("2000") @Min(1) @Max(10000) int confirmTimeoutMs,
        @DefaultValue("2") @Min(1) @Max(3) int publishAttempts,
        @DefaultValue("100") @Min(0) @Max(1000) int retryBackoffMs,
        @DefaultValue("65536") @Min(1) @Max(65536) int sourceBytes) {}

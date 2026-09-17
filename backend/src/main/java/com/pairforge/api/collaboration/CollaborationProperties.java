package com.pairforge.api.collaboration;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("pairforge.collaboration")
public record CollaborationProperties(
        @DefaultValue("86400") @Min(1) int ttlSeconds,
        @DefaultValue("65536") @Min(1) @Max(65536) int sourceBytes,
        @DefaultValue("400000") @Min(394240) @Max(1048576) int messageBytes,
        @DefaultValue("10") @Min(1) int messagesPerSecond,
        @DefaultValue("100") @Min(1) int maxConnections,
        @DefaultValue("10") @Min(1) int maxConnectionsPerIp,
        @DefaultValue("5") @Min(1) int maxConnectionsPerUser,
        @DefaultValue("5000") @Min(100) int connectTimeoutMs,
        @DefaultValue("5000") @Min(100) int sendTimeoutMs,
        @DefaultValue("1048576") @Min(400000) int sendBufferBytes) {}

package com.pairforge.api.auth;

import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("pairforge.auth")
public record AuthProperties(
        String keyHex,
        @NotBlank String issuer,
        @NotBlank String audience,
        @Min(60) @Max(3600) long tokenSeconds,
        @Min(4) @Max(16) int bcryptCost,
        @NotEmpty List<@NotBlank String> allowedOrigins,
        @Min(1) int loginIpLimit,
        @Min(1) int loginAccountLimit,
        @Min(1) int registrationIpLimit,
        @Min(1) int ipWindowSeconds,
        @Min(1) int accountWindowSeconds,
        @Min(1) int registrationWindowSeconds) {
    @AssertTrue(message = "Allowed origins must be explicit HTTP(S) origins without paths or wildcards")
    public boolean isAllowedOriginsExplicit() {
        if (allowedOrigins == null) return false;
        return allowedOrigins.stream().allMatch(origin -> {
            try {
                URI uri = URI.create(origin);
                return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                        && uri.getHost() != null && !origin.contains("*") && uri.getRawUserInfo() == null
                        && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                        && uri.getRawQuery() == null && uri.getRawFragment() == null
                        && (uri.getPort() == -1 || uri.getPort() >= 1 && uri.getPort() <= 65535);
            } catch (IllegalArgumentException | NullPointerException error) {
                return false;
            }
        });
    }
    @Override public String toString() { return "AuthProperties[redacted]"; }
}

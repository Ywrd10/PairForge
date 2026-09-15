package com.pairforge.api.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

@Service
public class TokenService {
    public record TokenResponse(String accessToken, String tokenType, long expiresIn, Instant expiresAt) {
        @Override public String toString() { return "TokenResponse[redacted]"; }
    }
    private final JwtEncoder encoder;
    private final AuthProperties properties;
    private final Clock clock;
    public TokenService(JwtEncoder encoder, AuthProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }
    public TokenResponse issue(UUID userId) {
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant expiry = now.plusSeconds(properties.tokenSeconds());
        var claims = JwtClaimsSet.builder().issuer(properties.issuer())
                .audience(java.util.List.of(properties.audience())).subject(userId.toString())
                .issuedAt(now).notBefore(now).expiresAt(expiry).build();
        var token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new TokenResponse(token, "Bearer", properties.tokenSeconds(), expiry);
    }
}

package com.pairforge.api.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import static org.assertj.core.api.Assertions.*;

class TokenSecurityTest {
    final TokenConfiguration configuration = new TokenConfiguration();
    final String keyHex = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    final Instant now = Instant.parse("2026-09-15T12:00:00Z");
    AuthProperties properties(String key) {
        return new AuthProperties(key, "pairforge-api", "pairforge-browser", 900, 4,
                List.of("http://127.0.0.1:5173"), 20, 10, 5, 60, 900, 3600);
    }
    JwtClaimsSet.Builder claims() {
        return JwtClaimsSet.builder().issuer("pairforge-api").subject(UUID.randomUUID().toString())
                .audience(List.of("pairforge-browser")).issuedAt(now).notBefore(now).expiresAt(now.plusSeconds(900));
    }
    String encode(JwtClaimsSet claims) {
        return configuration.jwtEncoder(configuration.jwtKey(properties(keyHex))).encode(
                JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
    JwtDecoder decoder() {
        return configuration.jwtDecoder(configuration.jwtKey(properties(keyHex)), properties(keyHex), Clock.fixed(now, ZoneOffset.UTC));
    }
    @Test void issuedTokensContainOnlyIdentityAndRequiredClaims() {
        var service = new TokenService(configuration.jwtEncoder(configuration.jwtKey(properties(keyHex))),
                properties(keyHex), Clock.fixed(now, ZoneOffset.UTC));
        UUID id = UUID.randomUUID();
        var response = service.issue(id);
        Jwt jwt = decoder().decode(response.accessToken());
        assertThat(jwt.getSubject()).isEqualTo(id.toString());
        assertThat(jwt.getClaims().keySet()).containsExactlyInAnyOrder("iss", "sub", "aud", "iat", "nbf", "exp");
        assertThat(response.expiresAt()).isEqualTo(now.plusSeconds(900));
        assertThat(response.toString()).doesNotContain(response.accessToken());
    }
    @ParameterizedTest
    @ValueSource(strings = {"expired", "expiry-boundary", "future", "issuer", "audience", "subject", "missing-exp", "missing-iat",
            "missing-nbf", "future-iat", "long-lived"})
    void rejectsInvalidClaims(String scenario) {
        var builder = claims();
        switch (scenario) {
            case "expired" -> builder.issuedAt(now.minusSeconds(1000)).notBefore(now.minusSeconds(1000)).expiresAt(now.minusSeconds(1));
            case "expiry-boundary" -> builder.issuedAt(now.minusSeconds(900)).notBefore(now.minusSeconds(900)).expiresAt(now);
            case "future" -> builder.notBefore(now.plusSeconds(60));
            case "issuer" -> builder.issuer("other");
            case "audience" -> builder.audience(List.of("other"));
            case "subject" -> builder.subject("not-a-user-id");
            case "missing-exp" -> builder.claims(map -> map.remove("exp"));
            case "missing-iat" -> builder.claims(map -> map.remove("iat"));
            case "missing-nbf" -> builder.claims(map -> map.remove("nbf"));
            case "future-iat" -> builder.issuedAt(now.plusSeconds(60));
            case "long-lived" -> builder.expiresAt(now.plusSeconds(901));
        }
        String token = encode(builder.build());
        assertThatThrownBy(() -> decoder().decode(token)).isInstanceOf(JwtException.class);
    }
    @Test void rejectsWrongKeyUnsignedMalformedAndWrongAlgorithmTokens() {
        var otherKey = new SecretKeySpec(new byte[64], "HmacSHA512");
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(otherKey));
        String wrongAlgorithm = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS512).build(), claims().build())).getTokenValue();
        String wrongKey = configuration.jwtEncoder(configuration.jwtKey(properties("a".repeat(64))))
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims().build())).getTokenValue();
        for (String token : List.of(wrongAlgorithm, wrongKey, "eyJhbGciOiJub25lIn0.e30.", "malformed")) {
            assertThatThrownBy(() -> decoder().decode(token)).isInstanceOf(JwtException.class);
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"", "short", "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"})
    void rejectsMissingOrMalformedSigningKeyWithoutDisclosingIt(String key) {
        assertThatThrownBy(() -> configuration.jwtKey(properties(key))).isInstanceOf(IllegalStateException.class)
                .hasMessage("JWT_KEY_HEX must encode exactly 32 random bytes as 64 hexadecimal characters");
        assertThat(properties(key).toString()).isEqualTo("AuthProperties[redacted]");
    }
}

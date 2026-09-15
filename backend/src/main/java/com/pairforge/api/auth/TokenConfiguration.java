package com.pairforge.api.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class TokenConfiguration {
    @Bean Clock authClock() { return Clock.systemUTC(); }

    @Bean SecretKey jwtKey(AuthProperties properties) {
        if (properties.keyHex() == null || !properties.keyHex().matches("[0-9a-fA-F]{64}")) {
            throw new IllegalStateException("JWT_KEY_HEX must encode exactly 32 random bytes as 64 hexadecimal characters");
        }
        return new SecretKeySpec(HexFormat.of().parseHex(properties.keyHex()), "HmacSHA256");
    }
    @Bean PasswordEncoder passwordEncoder(AuthProperties properties) {
        return new BCryptPasswordEncoder(properties.bcryptCost());
    }
    @Bean JwtEncoder jwtEncoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }
    @Bean JwtDecoder jwtDecoder(SecretKey key, AuthProperties properties, Clock clock) {
        var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        var time = new JwtTimestampValidator(Duration.ZERO);
        time.setClock(clock);
        OAuth2TokenValidator<Jwt> requiredClaims = jwt -> {
            try {
                UUID id = UUID.fromString(jwt.getSubject());
                boolean valid = id.toString().equals(jwt.getSubject())
                        && jwt.getAudience().contains(properties.audience())
                        && jwt.getExpiresAt() != null && jwt.getIssuedAt() != null && jwt.getNotBefore() != null
                        && jwt.getExpiresAt().isAfter(clock.instant())
                        && !jwt.getIssuedAt().isAfter(clock.instant())
                        && jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                        && !jwt.getExpiresAt().isAfter(jwt.getIssuedAt().plusSeconds(properties.tokenSeconds()));
                if (valid) return OAuth2TokenValidatorResult.success();
            } catch (IllegalArgumentException | NullPointerException ignored) {
                // All malformed identity/claim shapes have the same authentication failure.
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid access token", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(time,
                new JwtIssuerValidator(properties.issuer()), requiredClaims));
        return decoder;
    }
}

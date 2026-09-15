package com.pairforge.api.auth;

import jakarta.validation.Validation;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

class AuthPropertiesTest {
    private boolean validOrigin(String origin) {
        var properties = new AuthProperties(null, "pairforge-api", "pairforge-browser", 900, 4,
                List.of(origin), 20, 10, 5, 60, 900, 3600);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator().validate(properties).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:5173", "http://localhost:5173", "https://pairforge.example", "http://[::1]:5173"})
    void acceptsExplicitOrigins(String origin) {
        assertThat(validOrigin(origin)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "null", "https://*.example.com", "https://example.com/path",
            "https://example.com?query=value", "https://example.com#fragment", "https://user@example.com",
            "file:///tmp", "https://example.com:99999", ""})
    void rejectsUnsafeOrMalformedOrigins(String origin) {
        assertThat(validOrigin(origin)).isFalse();
    }
}

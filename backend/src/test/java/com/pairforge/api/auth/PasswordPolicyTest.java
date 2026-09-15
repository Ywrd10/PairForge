package com.pairforge.api.auth;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PasswordPolicyTest {
    final PasswordPolicyValidator policy = new PasswordPolicyValidator();
    @Test void enforcesCharacterAndUtf8BoundariesWithoutSilentTruncation() {
        assertThat(policy.isValid(null, null)).isFalse();
        assertThat(policy.isValid("x".repeat(14), null)).isFalse();
        assertThat(policy.isValid("x".repeat(15), null)).isTrue();
        assertThat(policy.isValid("x".repeat(72), null)).isTrue();
        assertThat(policy.isValid("x".repeat(73), null)).isFalse();
        assertThat(policy.isValid("é".repeat(36), null)).isTrue();
        assertThat(policy.isValid("é".repeat(37), null)).isFalse();
        assertThat(policy.isValid("x".repeat(20) + "\0", null)).isFalse();
        assertThat(policy.isValid("x".repeat(20) + "\uD800", null)).isFalse();
    }
    @Test void credentialsPreservePasswordAndNormalizeOnlyEmail() {
        var request = new AuthRequest(" Alice@Example.COM ", "  a long unchanged password  ");
        assertThat(request.email()).isEqualTo("alice@example.com");
        assertThat(request.password()).isEqualTo("  a long unchanged password  ");
        assertThat(request.toString()).isEqualTo("AuthRequest[redacted]");
    }
}

package com.pairforge.api.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;

public record AuthRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @PasswordPolicy String password) {
    public AuthRequest {
        if (email != null) email = email.trim().toLowerCase(Locale.ROOT);
    }
    @Override public String toString() { return "AuthRequest[redacted]"; }
}

package com.pairforge.api.user;

import java.util.Locale;
import java.util.Objects;

public final class Email {
    private Email() {}

    public static String normalize(String email) {
        String normalized = Objects.requireNonNull(email, "email").trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > 254) {
            throw new IllegalArgumentException("Email must contain 1 to 254 characters");
        }
        return normalized;
    }
}

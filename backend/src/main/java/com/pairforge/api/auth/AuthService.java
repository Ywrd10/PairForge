package com.pairforge.api.auth;

import com.pairforge.api.common.ApiException;
import com.pairforge.api.user.User;
import com.pairforge.api.user.UserRepository;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    public record UserResponse(UUID id, String email) {
        static UserResponse from(User user) { return new UserResponse(user.getId(), user.getEmail()); }
    }
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final AuthRateLimiter limiter;
    private final String dummyHash;
    public AuthService(UserRepository users, PasswordEncoder passwords, TokenService tokens, AuthRateLimiter limiter) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.limiter = limiter;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }
    public UserResponse register(AuthRequest request, String ip) {
        limiter.register(ip);
        // BCrypt runs outside the short repository transaction.
        String hash = passwords.encode(request.password());
        try {
            return UserResponse.from(users.saveAndFlush(new User(request.email(), hash)));
        } catch (DataIntegrityViolationException error) {
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && "users_email_unique".equals(violation.getConstraintName())) {
                    throw new ApiException(409, "EMAIL_ALREADY_REGISTERED", "Email is already registered");
                }
            }
            throw error;
        }
    }
    public TokenService.TokenResponse login(AuthRequest request, String ip) {
        limiter.login(ip, request.email());
        var user = users.findByEmail(request.email());
        boolean matches = passwords.matches(request.password(), user.map(User::getPasswordHash).orElse(dummyHash));
        if (!matches || user.isEmpty()) throw unauthorized();
        return tokens.issue(user.orElseThrow().getId());
    }
    public UserResponse me(UUID userId) {
        return UserResponse.from(users.findById(userId).orElseThrow(AuthService::unauthorized));
    }
    private static ApiException unauthorized() {
        return new ApiException(401, "UNAUTHORIZED", "Authentication is required or credentials are invalid");
    }
}

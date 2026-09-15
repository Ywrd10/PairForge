package com.pairforge.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    AuthService.UserResponse register(@Valid @RequestBody AuthRequest request, HttpServletRequest servlet) {
        return auth.register(request, servlet.getRemoteAddr());
    }
    @PostMapping("/login")
    TokenService.TokenResponse login(@Valid @RequestBody AuthRequest request, HttpServletRequest servlet) {
        return auth.login(request, servlet.getRemoteAddr());
    }
    @GetMapping("/me")
    AuthService.UserResponse me(@AuthenticationPrincipal Jwt token) {
        return auth.me(UUID.fromString(token.getSubject()));
    }
}

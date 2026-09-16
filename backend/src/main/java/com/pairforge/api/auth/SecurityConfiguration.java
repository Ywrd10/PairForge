package com.pairforge.api.auth;

import com.pairforge.api.common.ApiErrors;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.*;
import org.springframework.web.filter.CorsFilter;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {
    @Bean SecurityFilterChain security(HttpSecurity http, AuthProperties properties, ApiErrors errors) throws Exception {
        var corsConfig = new CorsConfiguration();
        corsConfig.setAllowedOrigins(properties.allowedOrigins());
        corsConfig.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        corsConfig.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        corsConfig.setExposedHeaders(List.of("X-Request-ID", "Retry-After"));
        corsConfig.setAllowCredentials(false);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", corsConfig);
        var cors = new CorsFilter(source);
        cors.setCorsProcessor(new DefaultCorsProcessor() {
            @Override protected void rejectRequest(ServerHttpResponse response) throws java.io.IOException {
                errors.write(((ServletServerHttpResponse) response).getServletResponse(),
                        403, "FORBIDDEN", "Origin or cross-origin request is not allowed");
            }
        });
        http.addFilterBefore(cors, org.springframework.security.web.csrf.CsrfFilter.class)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                // Credentials are never supplied automatically by the browser: bearer headers only.
                // Revisit CSRF before introducing cookie, session, or Basic authentication.
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness",
                                "/actuator/health/readiness").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/rooms", "/api/rooms/{roomId}").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/rooms", "/api/rooms/{roomId}/join").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, error) -> {
                            response.setHeader("WWW-Authenticate", "Bearer");
                            errors.write(response, 401, "UNAUTHORIZED", "Authentication is required or credentials are invalid");
                        })
                        .accessDeniedHandler((request, response, error) ->
                                errors.write(response, 403, "FORBIDDEN", "Access is not permitted")))
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {})
                        .accessDeniedHandler((request, response, error) ->
                                errors.write(response, 403, "FORBIDDEN", "Access is not permitted"))
                        .authenticationEntryPoint((request, response, error) -> {
                            response.setHeader("WWW-Authenticate", "Bearer");
                            errors.write(response, 401, "UNAUTHORIZED", "Authentication is required or credentials are invalid");
                        }));
        return http.build();
    }
}

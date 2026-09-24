package com.pairforge.api.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                             FilterChain chain) throws ServletException, IOException {
        // Run before security so rejected requests also carry a server-generated ID.
        String id = UUID.randomUUID().toString();
        response.setHeader("X-Request-ID", id);
        try (var ignored = org.slf4j.MDC.putCloseable("requestId", id)) {
            chain.doFilter(request, response);
        }
    }
}

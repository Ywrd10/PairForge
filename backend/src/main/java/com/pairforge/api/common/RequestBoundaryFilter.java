package com.pairforge.api.common;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(SecurityProperties.DEFAULT_FILTER_ORDER + 1)
public class RequestBoundaryFilter extends OncePerRequestFilter {
    private static final int MAX_JSON_BYTES = 4096;
    private final ApiErrors errors;
    public RequestBoundaryFilter(ApiErrors errors) { this.errors = errors; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                             FilterChain chain) throws ServletException, IOException {
        boolean execution = request.getMethod().equals("POST") && request.getServletPath().matches("/api/rooms/[^/]+/executions");
        int limit = execution ? 400000 : MAX_JSON_BYTES;
        boolean boundedBody = execution || request.getMethod().equals("POST") &&
                (request.getServletPath().equals("/api/auth/login")
                        || request.getServletPath().equals("/api/auth/register")
                        || request.getServletPath().equals("/api/rooms")
                        || request.getServletPath().matches("/api/rooms/[^/]+/join"));
        if (!boundedBody) {
            chain.doFilter(request, response);
            return;
        }
        boolean json;
        try {
            json = request.getContentType() != null
                    && MediaType.APPLICATION_JSON.includes(MediaType.parseMediaType(request.getContentType()));
        } catch (InvalidMediaTypeException error) {
            json = false;
        }
        if (!json) {
            errors.write(response, 415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json");
            return;
        }
        if (request.getContentLengthLong() > limit) {
            errors.write(response, 413, "REQUEST_TOO_LARGE", "Request body is limited to " + limit + " bytes");
            return;
        }
        byte[] body = request.getInputStream().readNBytes(limit + 1);
        if (body.length > limit) {
            errors.write(response, 413, "REQUEST_TOO_LARGE", "Request body is limited to " + limit + " bytes");
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var input = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) {
                        throw new UnsupportedOperationException("Synchronous JSON requests only");
                    }
                };
            }
            @Override public BufferedReader getReader() {
                return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
            }
        }, response);
    }
}

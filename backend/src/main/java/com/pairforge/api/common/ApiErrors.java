package com.pairforge.api.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ApiErrors {
    public record ErrorBody(String code, String message, String requestId, Map<String, String> fieldErrors) {}
    private final ObjectMapper mapper;
    public ApiErrors(ObjectMapper mapper) { this.mapper = mapper; }

    public void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        write(response, status, code, message, Map.of());
    }
    public void write(HttpServletResponse response, int status, String code, String message,
                      Map<String, String> fields) throws IOException {
        response.setStatus(status);
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(response.getOutputStream(),
                new ErrorBody(code, message, response.getHeader("X-Request-ID"), fields));
    }
}

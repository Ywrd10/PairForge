package com.pairforge.api.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ApiErrors {
    public record DispatchErrorBody(String code, String message, String requestId, Map<String, String> fieldErrors,
            java.util.UUID executionId, com.pairforge.api.execution.ExecutionStatus status,
            com.pairforge.api.execution.FailureReason failureReason, boolean outcomeUnknown) {}
    public record ErrorBody(String code, String message, String requestId, Map<String, String> fieldErrors) {}
    private final ObjectMapper mapper;
    public ApiErrors(ObjectMapper mapper) { this.mapper = mapper; }

    public void dispatch(HttpServletResponse response, com.pairforge.api.execution.ExecutionDispatchException error) throws IOException {
        response.setStatus(503);
        response.setHeader("Location", "/api/executions/" + error.executionId());
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json");
        mapper.writeValue(response.getOutputStream(), new DispatchErrorBody(
                error.status() == null ? "EXECUTION_OUTCOME_UNKNOWN" : error.failureReason().name(),
                error.getMessage(), response.getHeader("X-Request-ID"), Map.of(), error.executionId(),
                error.status(), error.failureReason(), error.status() == null));
    }

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

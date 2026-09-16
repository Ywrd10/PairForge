package com.pairforge.api.common;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingPathVariableException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final ApiErrors errors;
    public ApiExceptionHandler(ApiErrors errors) { this.errors = errors; }

    @ExceptionHandler(ApiException.class)
    void api(ApiException error, HttpServletResponse response) throws IOException {
        if (error.status() >= 500) LOG.warn("Dependency failure code={} requestId={}",
                error.code(), response.getHeader("X-Request-ID"));
        if (error.retryAfter() != null) response.setHeader("Retry-After", error.retryAfter().toString());
        errors.write(response, error.status(), error.code(), error.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    void validation(MethodArgumentNotValidException error, HttpServletResponse response) throws IOException {
        var fields = new TreeMap<String, String>();
        error.getBindingResult().getFieldErrors().forEach(field ->
                fields.putIfAbsent(field.getField(), field.getDefaultMessage()));
        errors.write(response, 400, "INVALID_INPUT", "Request validation failed", fields);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    void malformed(HttpServletResponse response) throws IOException {
        errors.write(response, 400, "INVALID_INPUT", "A valid JSON request body is required");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    void mediaType(HttpServletResponse response) throws IOException {
        errors.write(response, 415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    void invalidParameter(HttpServletResponse response) throws IOException {
        errors.write(response, 400, "INVALID_INPUT", "Invalid path or query parameter");
    }

    @ExceptionHandler(MissingPathVariableException.class)
    void missingPathVariable(MissingPathVariableException error, HttpServletResponse response) throws IOException {
        // A whitespace-only UUID converts to null; that is invalid client input.
        // A genuinely missing template variable still indicates a server mapping bug.
        if (error.isMissingAfterConversion()) invalidParameter(response);
        else unexpected(error, response);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    void method(HttpServletResponse response) throws IOException {
        errors.write(response, 405, "METHOD_NOT_ALLOWED", "Method is not supported");
    }

    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class, TransactionSystemException.class})
    void dependency(RuntimeException error, HttpServletResponse response) throws IOException {
        // SQL exception text may contain credentials, emails, or rejected row values.
        LOG.warn("Persistence failure type={} requestId={}", error.getClass().getSimpleName(),
                response.getHeader("X-Request-ID"));
        errors.write(response, 503, "DEPENDENCY_UNAVAILABLE", "A required dependency is unavailable");
    }

    @ExceptionHandler(Exception.class)
    void unexpected(Exception error, HttpServletResponse response) throws IOException {
        LOG.error("Unhandled error type={} requestId={}", error.getClass().getName(),
                response.getHeader("X-Request-ID"));
        errors.write(response, 500, "INTERNAL_ERROR", "An unexpected error occurred");
    }
}

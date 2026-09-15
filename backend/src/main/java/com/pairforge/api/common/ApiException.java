package com.pairforge.api.common;

public class ApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final Long retryAfter;

    public ApiException(int status, String code, String message) {
        this(status, code, message, null);
    }
    public ApiException(int status, String code, String message, Long retryAfter) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfter = retryAfter;
    }
    public int status() { return status; }
    public String code() { return code; }
    public Long retryAfter() { return retryAfter; }
}

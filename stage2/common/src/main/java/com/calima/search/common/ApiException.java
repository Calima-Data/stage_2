package com.calima.search.common;

/** A documented API error: an HTTP status plus the stable code that clients branch on. */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final Integer retryAfterSeconds;

    public ApiException(int status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(int status, String code, String message, Integer retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }
}

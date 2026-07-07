package com.techdesksystem.techdesk.auth.exception;

import org.springframework.http.HttpStatus;

public class AuthException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public AuthException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static AuthException badRequest(String code, String message) {
        return new AuthException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static AuthException unauthorized(String code, String message) {
        return new AuthException(HttpStatus.UNAUTHORIZED, code, message);
    }

    public static AuthException forbidden(String code, String message) {
        return new AuthException(HttpStatus.FORBIDDEN, code, message);
    }

    public static AuthException notFound(String code, String message) {
        return new AuthException(HttpStatus.NOT_FOUND, code, message);
    }

    public static AuthException conflict(String code, String message) {
        return new AuthException(HttpStatus.CONFLICT, code, message);
    }
}

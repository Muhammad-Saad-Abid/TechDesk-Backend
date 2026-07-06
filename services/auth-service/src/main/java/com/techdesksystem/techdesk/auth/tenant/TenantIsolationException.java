package com.techdesksystem.techdesk.auth.tenant;

public class TenantIsolationException extends RuntimeException {

    public TenantIsolationException(String message) {
        super(message);
    }

    public TenantIsolationException(String message, Throwable cause) {
        super(message, cause);
    }
}

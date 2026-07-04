package com.techdesksystem.techdesk.tenant.exception;

import org.springframework.http.HttpStatus;

public class TenantException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public TenantException(HttpStatus status, String code, String message) {
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

    public static TenantException conflict() {
        return new TenantException(
                HttpStatus.CONFLICT,
                "TENANT_ALREADY_EXISTS",
                "A tenant with this slug already exists."
        );
    }

    public static TenantException notFound() {
        return new TenantException(
                HttpStatus.NOT_FOUND,
                "TENANT_NOT_FOUND",
                "Tenant was not found."
        );
    }

    public static TenantException provisioningFailed() {
        return new TenantException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "TENANT_PROVISIONING_FAILED",
                "Tenant provisioning failed and was rolled back."
        );
    }
}

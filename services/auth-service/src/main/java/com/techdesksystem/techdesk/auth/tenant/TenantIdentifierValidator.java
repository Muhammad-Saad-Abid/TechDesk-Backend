package com.techdesksystem.techdesk.auth.tenant;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class TenantIdentifierValidator {

    private static final Pattern SAFE_TENANT_IDENTIFIER =
            Pattern.compile("^tenant_[a-z][a-z0-9_]{0,55}$");

    public String requireValid(String tenantIdentifier) {
        if (tenantIdentifier == null
                || !SAFE_TENANT_IDENTIFIER.matcher(tenantIdentifier).matches()) {
            throw new TenantIsolationException(
                    "The tenant identifier is missing or invalid."
            );
        }
        return tenantIdentifier;
    }

    public boolean isValid(String tenantIdentifier) {
        return tenantIdentifier != null
                && SAFE_TENANT_IDENTIFIER.matcher(tenantIdentifier).matches();
    }
}

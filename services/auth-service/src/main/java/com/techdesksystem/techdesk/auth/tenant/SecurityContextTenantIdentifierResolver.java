package com.techdesksystem.techdesk.auth.tenant;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class SecurityContextTenantIdentifierResolver
        implements CurrentTenantIdentifierResolver<String> {

    static final String BOOTSTRAP_SCHEMA = "public";

    private final TenantIdentifierValidator validator;

    public SecurityContextTenantIdentifierResolver(
            TenantIdentifierValidator validator
    ) {
        this.validator = validator;
    }

    @Override
    public String resolveCurrentTenantIdentifier() {
        String securityTenant = tenantFromSecurityContext();
        String requestTenant = TenantContext.currentTenant().orElse(null);

        if (securityTenant != null && requestTenant != null
                && !securityTenant.equals(requestTenant)) {
            throw new TenantIsolationException(
                    "JWT tenant does not match the active request tenant."
            );
        }

        String resolved = securityTenant != null ? securityTenant : requestTenant;
        return resolved == null
                ? BOOTSTRAP_SCHEMA
                : validator.requireValid(resolved);
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }

    private String tenantFromSecurityContext() {
        Authentication authentication = SecurityContextHolder.getContext()
                .getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof Jwt jwt) {
            String claim = jwt.getClaimAsString("tenantId");
            return claim == null ? null : validator.requireValid(claim);
        }
        return null;
    }
}

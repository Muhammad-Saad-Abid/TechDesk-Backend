package com.techdesksystem.techdesk.auth.tenant;

import com.techdesksystem.techdesk.auth.config.AuthMultitenancyProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

@Component
public class TenantContextFilter extends OncePerRequestFilter {

    public static final String TENANT_HEADER = "X-Tenant-ID";

    private final AuthMultitenancyProperties properties;
    private final TenantIdentifierValidator validator;
    private final ObjectMapper objectMapper;

    public TenantContextFilter(
            AuthMultitenancyProperties properties,
            TenantIdentifierValidator validator,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        TenantContext.clear();
        if (!properties.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            filterWithTenantContext(request, response, filterChain);
        } catch (TenantIsolationException exception) {
            if (response.isCommitted()) {
                throw exception;
            }
            rejectRequest(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private void filterWithTenantContext(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String headerTenant = trimToNull(request.getHeader(TENANT_HEADER));
        String jwtTenant = tenantFromAuthenticatedJwt();

        if (headerTenant != null) {
            headerTenant = validator.requireValid(headerTenant);
        }
        if (jwtTenant != null) {
            jwtTenant = validator.requireValid(jwtTenant);
        }
        if (headerTenant != null && jwtTenant != null
                && !headerTenant.equals(jwtTenant)) {
            throw new TenantIsolationException(
                    "JWT tenant does not match the gateway tenant header."
            );
        }

        String resolvedTenant = jwtTenant != null ? jwtTenant : headerTenant;
        if (resolvedTenant == null && request.getRequestURI().startsWith("/api/")) {
            throw new TenantIsolationException(
                    "A trusted tenant context is required for API requests."
            );
        }

        if (resolvedTenant == null) {
            filterChain.doFilter(request, response);
            return;
        }

        try (TenantContext.Scope ignored = TenantContext.open(resolvedTenant)) {
            filterChain.doFilter(request, response);
        }
    }

    private void rejectRequest(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        response.resetBuffer();
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Map.of(
                "timestamp", Instant.now().toString(),
                "status", HttpServletResponse.SC_FORBIDDEN,
                "code", "TENANT_ISOLATION_VIOLATION",
                "message", "The request tenant context is invalid.",
                "path", request.getRequestURI(),
                "fieldErrors", Map.of()
        ));
    }

    private String tenantFromAuthenticatedJwt() {
        Authentication authentication = SecurityContextHolder.getContext()
                .getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            return trimToNull(jwt.getClaimAsString("tenantId"));
        }
        return null;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

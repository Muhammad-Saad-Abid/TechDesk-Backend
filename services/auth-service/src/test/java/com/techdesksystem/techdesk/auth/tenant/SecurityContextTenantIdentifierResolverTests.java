package com.techdesksystem.techdesk.auth.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityContextTenantIdentifierResolverTests {

    private final SecurityContextTenantIdentifierResolver resolver =
            new SecurityContextTenantIdentifierResolver(
                    new TenantIdentifierValidator()
            );

    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatedJwtTenantHasPriority() {
        SecurityContextHolder.getContext().setAuthentication(
                authenticatedJwt("tenant_alpha")
        );

        assertThat(resolver.resolveCurrentTenantIdentifier())
                .isEqualTo("tenant_alpha");
    }

    @Test
    void securityAndRequestContextMismatchIsRejected() {
        SecurityContextHolder.getContext().setAuthentication(
                authenticatedJwt("tenant_bravo")
        );

        try (TenantContext.Scope ignored = TenantContext.open("tenant_alpha")) {
            assertThatThrownBy(resolver::resolveCurrentTenantIdentifier)
                    .isInstanceOf(TenantIsolationException.class);
        }
    }

    private Jwt jwt(String tenant) {
        Instant issuedAt = Instant.now();
        return Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .subject("user@example.com")
                .claim("tenantId", tenant)
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(300))
                .build();
    }

    private JwtAuthenticationToken authenticatedJwt(String tenant) {
        return new JwtAuthenticationToken(
                jwt(tenant),
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
    }
}

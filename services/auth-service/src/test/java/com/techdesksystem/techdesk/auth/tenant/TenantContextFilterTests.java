package com.techdesksystem.techdesk.auth.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.techdesksystem.techdesk.auth.config.AuthMultitenancyProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TenantContextFilterTests {

    private final TenantContextFilter filter = filter();

    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void matchingGatewayHeaderIsAvailableOnlyDuringRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/auth/login"
        );
        request.addHeader(TenantContextFilter.TENANT_HEADER, "tenant_alpha");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(TenantContext.currentTenant()).isEmpty();
    }

    @Test
    void jwtAndHeaderMismatchReturnsSafeForbiddenResponse() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(
                        jwt("tenant_bravo"),
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))
                )
        );
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/protected"
        );
        request.addHeader(TenantContextFilter.TENANT_HEADER, "tenant_alpha");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
                .contains("TENANT_ISOLATION_VIOLATION")
                .doesNotContain("tenant_alpha")
                .doesNotContain("tenant_bravo");
        assertThat(TenantContext.currentTenant()).isEmpty();
    }

    private TenantContextFilter filter() {
        AuthMultitenancyProperties properties = new AuthMultitenancyProperties();
        properties.setEnabled(true);
        return new TenantContextFilter(
                properties,
                new TenantIdentifierValidator(),
                new ObjectMapper()
        );
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
}

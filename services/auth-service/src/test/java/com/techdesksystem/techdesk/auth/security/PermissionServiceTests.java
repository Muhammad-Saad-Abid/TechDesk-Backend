package com.techdesksystem.techdesk.auth.security;

import com.techdesksystem.techdesk.auth.exception.AuthException;
import com.techdesksystem.techdesk.auth.repository.PermissionRepository;
import com.techdesksystem.techdesk.auth.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermissionServiceTests {

    @Mock
    private PermissionRepository permissionRepository;

    @AfterEach
    void cleanContext() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void currentUserHasPermissionChecksTenantLocalAssignments() {
        PermissionService service = service();
        SecurityContextHolder.getContext().setAuthentication(jwt(42L));
        when(permissionRepository.findEffectivePermissionCodesByUserId(42L))
                .thenReturn(List.of(
                        "users:read",
                        "users:update"
                ));

        try (TenantContext.Scope ignored = TenantContext.open("tenant_alpha")) {
            assertThat(service.currentUserHasPermission("users:update"))
                    .isTrue();
            assertThat(service.currentUserHasPermission("roles:delete"))
                    .isFalse();
        }

        verify(permissionRepository, times(2))
                .findEffectivePermissionCodesByUserId(42L);
    }

    @Test
    void currentUserIdRejectsMissingJwtPrincipal() {
        PermissionService service = service();

        assertThatThrownBy(service::currentUserId)
                .isInstanceOfSatisfying(AuthException.class, exception ->
                        assertThat(exception.getCode())
                                .isEqualTo("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void currentUserIdRejectsInvalidUserIdClaim() {
        PermissionService service = service();
        SecurityContextHolder.getContext().setAuthentication(jwt("not-a-number"));

        assertThatThrownBy(service::currentUserId)
                .isInstanceOfSatisfying(AuthException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("INVALID_TOKEN"));
    }

    private PermissionService service() {
        return new PermissionService(permissionRepository);
    }

    private JwtAuthenticationToken jwt(Object userId) {
        Jwt jwt = new Jwt(
                "token",
                Instant.now(),
                Instant.now().plusSeconds(60),
                Map.of("alg", "HS256"),
                Map.of(
                        "userId", userId,
                        "tenantId", "tenant_alpha",
                        "tokenType", "access"
                )
        );
        return new JwtAuthenticationToken(
                jwt,
                List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))
        );
    }
}

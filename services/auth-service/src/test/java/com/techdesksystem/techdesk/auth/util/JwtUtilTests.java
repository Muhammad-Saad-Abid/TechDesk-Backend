package com.techdesksystem.techdesk.auth.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.techdesksystem.techdesk.auth.config.JwtProperties;
import com.techdesksystem.techdesk.auth.entity.User;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

class JwtUtilTests {

    @Test
    void accessTokenContainsRequiredIdentityAndPermissionClaims() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(
                "test-only-secret-key-that-is-at-least-32-bytes-long"
        );
        properties.setAccessTokenMinutes(15);
        properties.setRefreshTokenDays(7);

        JwtUtil jwtUtil = new JwtUtil(properties);
        User user = new User();
        user.setTenantId("tenant_claims_test");
        user.setEmail("claims@example.com");
        user.setRole("EMPLOYEE");
        ReflectionTestUtils.setField(user, "id", 42L);

        String token = jwtUtil.generateAccessToken(user);
        Claims claims = jwtUtil.parseAccessToken(token);
        String headerJson = new String(
                Base64.getUrlDecoder().decode(token.split("\\.")[0]),
                StandardCharsets.UTF_8
        );

        assertThat(headerJson).contains("\"alg\":\"HS256\"");
        assertThat(claims.getSubject()).isEqualTo("claims@example.com");
        assertThat(claims.get("userId", Number.class).longValue()).isEqualTo(42L);
        assertThat(claims.get("tenantId", String.class))
                .isEqualTo("tenant_claims_test");
        assertThat(claims.get("role", String.class)).isEqualTo("EMPLOYEE");
        List<?> permissions = claims.get("permissions", List.class);
        assertThat(permissions).isEmpty();
        assertThat(claims.get("tokenType", String.class)).isEqualTo("access");
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());

        Instant issuedAt = Instant.now();
        String refreshToken = jwtUtil.generateRefreshToken(
                user,
                issuedAt,
                issuedAt.plusSeconds(604800)
        );
        Claims refreshClaims = jwtUtil.parseAccessToken(refreshToken);

        assertThat(jwtUtil.isRefreshTokenValid(refreshToken)).isTrue();
        assertThat(jwtUtil.isAccessTokenValid(refreshToken)).isFalse();
        assertThat(refreshClaims.getId()).isNotBlank();
        assertThat(refreshClaims.get("tokenType", String.class))
                .isEqualTo("refresh");
    }
}

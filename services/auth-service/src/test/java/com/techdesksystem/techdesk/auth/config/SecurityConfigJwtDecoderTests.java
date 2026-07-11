package com.techdesksystem.techdesk.auth.config;

import com.techdesksystem.techdesk.auth.entity.User;
import com.techdesksystem.techdesk.auth.util.JwtUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityConfigJwtDecoderTests {

    private static final String SECRET =
            "test-only-secret-key-that-is-at-least-32-bytes-long";

    private JwtDecoder decoder;
    private JwtUtil jwtUtil;
    private User user;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setAccessTokenMinutes(15);
        properties.setRefreshTokenDays(7);

        decoder = new SecurityConfig().jwtDecoder(properties);
        jwtUtil = new JwtUtil(properties);

        user = new User();
        ReflectionTestUtils.setField(user, "id", 42L);
        user.setTenantId("tenant_security_test");
        user.setEmail("security@example.com");
        user.setRole("COMPANY_ADMIN");
    }

    @Test
    void acceptsSignedAccessToken() {
        Jwt decoded = decoder.decode(jwtUtil.generateAccessToken(user));

        assertThat(decoded.getClaimAsString("tokenType")).isEqualTo("access");
        assertThat(decoded.getClaimAsString("tenantId"))
                .isEqualTo("tenant_security_test");
    }

    @Test
    void rejectsSignedRefreshToken() {
        Instant issuedAt = Instant.now();
        String refreshToken = jwtUtil.generateRefreshToken(
                user,
                issuedAt,
                issuedAt.plusSeconds(3600)
        );

        assertThatThrownBy(() -> decoder.decode(refreshToken))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("Only access tokens are accepted.");
    }

    @Test
    void rejectsSignedAccessTokenWithoutExpirationClaim() {
        String token = Jwts.builder()
                .subject("security@example.com")
                .claim("tokenType", "access")
                .issuedAt(Date.from(Instant.now()))
                .signWith(
                        Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)),
                        Jwts.SIG.HS256
                )
                .compact();

        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("Expiration claim is required.");
    }
}

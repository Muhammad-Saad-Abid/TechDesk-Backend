package com.techdesksystem.techdesk.tenant.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityConfigJwtDecoderTests {

    private static final String SECRET =
            "test-only-secret-key-that-is-at-least-32-bytes-long";

    private JwtDecoder decoder;

    @BeforeEach
    void setUp() {
        decoder = new SecurityConfig().jwtDecoder(
                new TenantSecurityProperties(SECRET)
        );
    }

    @Test
    void acceptsSignedAccessTokenWithExpirationClaim() {
        Jwt decoded = decoder.decode(token("access", true));

        assertThat(decoded.getClaimAsString("tokenType")).isEqualTo("access");
        assertThat(decoded.getExpiresAt()).isNotNull();
    }

    @Test
    void rejectsSignedRefreshToken() {
        assertThatThrownBy(() -> decoder.decode(token("refresh", true)))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("Only access tokens are accepted.");
    }

    @Test
    void rejectsSignedAccessTokenWithoutExpirationClaim() {
        assertThatThrownBy(() -> decoder.decode(token("access", false)))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("Expiration claim is required.");
    }

    private String token(String tokenType, boolean includeExpiration) {
        Instant issuedAt = Instant.now();
        var builder = Jwts.builder()
                .subject("platform-admin@example.com")
                .claim("tokenType", tokenType)
                .issuedAt(Date.from(issuedAt));

        if (includeExpiration) {
            builder.expiration(Date.from(issuedAt.plusSeconds(900)));
        }

        return builder
                .signWith(
                        Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)),
                        Jwts.SIG.HS256
                )
                .compact();
    }
}

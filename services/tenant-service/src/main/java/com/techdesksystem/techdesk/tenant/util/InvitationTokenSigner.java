package com.techdesksystem.techdesk.tenant.util;

import com.techdesksystem.techdesk.tenant.config.TenantSecurityProperties;
import com.techdesksystem.techdesk.tenant.entity.TenantNotificationOutbox;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Component
public class InvitationTokenSigner {

    private final SecretKey signingKey;

    public InvitationTokenSigner(TenantSecurityProperties properties) {
        byte[] secret = properties.jwtSecret().getBytes(StandardCharsets.UTF_8);

        if (secret.length < 32) {
            throw new IllegalStateException(
                    "AUTH_JWT_SECRET must be at least 32 bytes."
            );
        }

        this.signingKey = Keys.hmacShaKeyFor(secret);
    }

    public String sign(TenantNotificationOutbox event) {
        Instant issuedAt = Instant.now();

        return Jwts.builder()
                .id(event.getInvitationJti())
                .issuer("techdesk-tenant-service")
                .subject(event.getRecipientEmail())
                .claim("userId", event.getAdminUserId())
                .claim("tenantId", event.getSchemaName())
                .claim("tokenType", "tenant-invitation")
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(event.getInvitationExpiresAt()))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }
}

package com.techdesksystem.techdesk.gateway.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techdesksystem.techdesk.gateway.tenant.TenantResolutionProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class GatewayJwtVerifier {

    private static final Pattern SCHEMA_NAME_PATTERN =
            Pattern.compile("^tenant_[a-z][a-z0-9_]{0,55}$");

    private final TenantResolutionProperties tenantResolutionProperties;
    private final ObjectMapper objectMapper;

    public GatewayJwtVerifier(
            TenantResolutionProperties tenantResolutionProperties,
            ObjectMapper objectMapper
    ) {
        this.tenantResolutionProperties = tenantResolutionProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * Verifies a bearer access token locally at the gateway. Invalid, expired,
     * unsigned, non-access, or malformed tokens return Optional.empty().
     */
    public Optional<VerifiedJwtClaims> verifyAccessToken(String authorization) {
        if (authorization == null
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                || tenantResolutionProperties.jwtHmacSecret() == null
                || tenantResolutionProperties.jwtHmacSecret().isBlank()) {
            return Optional.empty();
        }

        String token = authorization.substring(7).trim();
        String[] parts = token.split("\\.", -1);

        if (parts.length != 3) {
            return Optional.empty();
        }

        try {
            JsonNode header = objectMapper.readTree(decodeBase64Url(parts[0]));
            if (!"HS256".equals(header.path("alg").asText())) {
                return Optional.empty();
            }

            byte[] suppliedSignature = decodeBase64Url(parts[2]);
            byte[] expectedSignature = sign(parts[0] + "." + parts[1]);
            if (!MessageDigest.isEqual(expectedSignature, suppliedSignature)) {
                return Optional.empty();
            }

            JsonNode claims = objectMapper.readTree(decodeBase64Url(parts[1]));
            if (!claims.hasNonNull("exp")
                    || !claims.path("exp").canConvertToLong()
                    || claims.path("exp").asLong() <= Instant.now().getEpochSecond()
                    || !"access".equals(claims.path("tokenType").asText())) {
                return Optional.empty();
            }

            String tenantId = normalizedTenant(claims.path("tenantId").asText());
            if (tenantId == null) {
                return Optional.empty();
            }

            return Optional.of(new VerifiedJwtClaims(
                    tenantId,
                    normalizedRole(claims.path("role").asText(null)),
                    permissions(claims.path("permissions"))
            ));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private byte[] sign(String unsignedToken) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec key = new SecretKeySpec(
                    tenantResolutionProperties.jwtHmacSecret()
                            .getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            );

            mac.init(key);
            return mac.doFinal(unsignedToken.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to verify JWT signature.", exception);
        }
    }

    private byte[] decodeBase64Url(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private String normalizedTenant(String candidate) {
        if (candidate == null) {
            return null;
        }

        String normalized = candidate.trim().toLowerCase(Locale.ROOT);
        return SCHEMA_NAME_PATTERN.matcher(normalized).matches()
                ? normalized
                : null;
    }

    private String normalizedRole(String role) {
        return role == null || role.isBlank()
                ? null
                : role.trim().toUpperCase(Locale.ROOT);
    }

    private List<String> permissions(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }

        List<String> permissions = new ArrayList<>();
        node.forEach(permission -> {
            if (permission.isTextual() && !permission.asText().isBlank()) {
                permissions.add(permission.asText());
            }
        });
        return List.copyOf(permissions);
    }

    public Optional<VerifiedJwtClaims> verifyAccessToken(HttpHeaders headers) {
        return verifyAccessToken(headers.getFirst(HttpHeaders.AUTHORIZATION));
    }
}

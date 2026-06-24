package com.techdesksystem.techdesk.gateway.tenant;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Component
public class TenantResolutionGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(TenantResolutionGlobalFilter.class);

    private static final String TENANT_HEADER = "X-Tenant-ID";

    private static final Pattern SCHEMA_NAME_PATTERN =
            Pattern.compile("^tenant_[a-z][a-z0-9_]{0,55}$");

    private final TenantResolutionProperties properties;
    private final ObjectMapper objectMapper;

    public TenantResolutionGlobalFilter(
            TenantResolutionProperties properties,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Optional<String> hostTenant = resolveFromHost(exchange);
        Optional<String> jwtTenant = resolveFromJwt(exchange.getRequest());

        if (hostTenant.isPresent()
                && jwtTenant.isPresent()
                && !hostTenant.get().equals(jwtTenant.get())) {
            LOGGER.warn(
                    "Tenant mismatch detected. Host tenant '{}' takes precedence.",
                    hostTenant.get()
            );
        }

        String resolvedTenant = hostTenant.orElseGet(() -> jwtTenant.orElse(null));

        ServerHttpRequest request = exchange.getRequest()
                .mutate()
                .headers(headers -> {
                    headers.remove(TENANT_HEADER);

                    if (resolvedTenant != null) {
                        headers.set(TENANT_HEADER, resolvedTenant);
                    }
                })
                .build();

        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    private Optional<String> resolveFromHost(ServerWebExchange exchange) {
        InetSocketAddress host = exchange.getRequest().getHeaders().getHost();

        if (host == null) {
            return Optional.empty();
        }

        String hostname = host.getHostString().toLowerCase(Locale.ROOT);

        for (String configuredSuffix : properties.hostSuffixes()) {
            String suffix = configuredSuffix.toLowerCase(Locale.ROOT);

            if (!suffix.startsWith(".")) {
                suffix = "." + suffix;
            }

            if (hostname.endsWith(suffix)) {
                String subdomain =
                        hostname.substring(0, hostname.length() - suffix.length());

                if (!subdomain.contains(".")) {
                    return schemaNameFromSubdomain(subdomain);
                }
            }
        }

        return Optional.empty();
    }

    private Optional<String> schemaNameFromSubdomain(String subdomain) {
        String normalizedSlug = subdomain
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_');

        if (!normalizedSlug.matches("^[a-z][a-z0-9_]{0,55}$")) {
            return Optional.empty();
        }

        return validateSchemaName("tenant_" + normalizedSlug);
    }

    private Optional<String> resolveFromJwt(ServerHttpRequest request) {
        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authorization == null
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                || properties.jwtHmacSecret() == null
                || properties.jwtHmacSecret().isBlank()) {
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
                    || claims.path("exp").asLong() <= Instant.now().getEpochSecond()) {
                return Optional.empty();
            }

            return validateSchemaName(claims.path("tenantId").asText());
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private byte[] sign(String unsignedToken) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec key = new SecretKeySpec(
                    properties.jwtHmacSecret().getBytes(StandardCharsets.UTF_8),
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

    private Optional<String> validateSchemaName(String candidate) {
        String normalized = candidate.trim().toLowerCase(Locale.ROOT);

        return SCHEMA_NAME_PATTERN.matcher(normalized).matches()
                ? Optional.of(normalized)
                : Optional.empty();
    }
}
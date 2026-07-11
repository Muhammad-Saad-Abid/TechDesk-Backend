package com.techdesksystem.techdesk.gateway.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.techdesksystem.techdesk.gateway.security.GatewayJwtVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

class TenantResolutionGlobalFilterTests {

    private static final String SECRET =
            "test-secret-that-is-at-least-thirty-two-bytes-long";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TenantResolutionProperties properties =
            new TenantResolutionProperties(
                    List.of(".techdesk.local", ".techdesk.com"),
                    SECRET
            );
    private final TenantResolutionGlobalFilter filter =
            new TenantResolutionGlobalFilter(
                    properties,
                    new GatewayJwtVerifier(properties, objectMapper)
            );

    @Test
    void resolvesTenantFromSubdomain() {
        ServerWebExchange forwarded = filter(
                MockServerHttpRequest
                        .get("http://company-a.techdesk.local/api/tickets")
                        .header(HttpHeaders.HOST, "company-a.techdesk.local")
                        .build()
        );

        assertThat(tenantHeader(forwarded)).isEqualTo("tenant_company_a");
    }

    @Test
    void resolvesTenantFromValidJwtWhenHostDoesNotIdentifyTenant() throws Exception {
        String token = createToken(
                SECRET,
                "tenant_from_jwt",
                Instant.now().plusSeconds(300)
        );

        ServerWebExchange forwarded = filter(
                MockServerHttpRequest
                        .get("http://localhost/api/tickets")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build()
        );

        assertThat(tenantHeader(forwarded)).isEqualTo("tenant_from_jwt");
    }

    @Test
    void hostTenantTakesPrecedenceOverJwtTenant() throws Exception {
        String token = createToken(
                SECRET,
                "tenant_from_jwt",
                Instant.now().plusSeconds(300)
        );

        ServerWebExchange forwarded = filter(
                MockServerHttpRequest
                        .get("http://host-tenant.techdesk.com/api/tickets")
                        .header(HttpHeaders.HOST, "host-tenant.techdesk.com")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build()
        );

        assertThat(tenantHeader(forwarded)).isEqualTo("tenant_host_tenant");
    }

    @Test
    void removesClientSuppliedTenantHeaderWhenTenantCannotBeResolved() {
        ServerWebExchange forwarded = filter(
                MockServerHttpRequest
                        .get("http://localhost/api/tickets")
                        .header("X-Tenant-ID", "tenant_attacker")
                        .build()
        );

        assertThat(tenantHeader(forwarded)).isNull();
    }

    @Test
    void ignoresJwtWithInvalidSignature() throws Exception {
        String token = createToken(
                "a-different-secret-that-is-also-long-enough",
                "tenant_attacker",
                Instant.now().plusSeconds(300)
        );

        ServerWebExchange forwarded = filter(
                MockServerHttpRequest
                        .get("http://localhost/api/tickets")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build()
        );

        assertThat(tenantHeader(forwarded)).isNull();
    }

    @Test
    void ignoresExpiredJwt() throws Exception {
        String token = createToken(
                SECRET,
                "tenant_expired",
                Instant.now().minusSeconds(1)
        );

        ServerWebExchange forwarded = filter(
                MockServerHttpRequest
                        .get("http://localhost/api/tickets")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build()
        );

        assertThat(tenantHeader(forwarded)).isNull();
    }

    @Test
    void ignoresRefreshJwtUsedAsAuthorizationBearer() throws Exception {
        String token = createToken(
                SECRET,
                "tenant_refresh_token",
                Instant.now().plusSeconds(300),
                "refresh"
        );

        ServerWebExchange forwarded = filter(
                MockServerHttpRequest
                        .get("http://localhost/api/tickets")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build()
        );

        assertThat(tenantHeader(forwarded)).isNull();
    }

    private ServerWebExchange filter(MockServerHttpRequest request) {
        AtomicReference<ServerWebExchange> forwardedExchange =
                new AtomicReference<>();

        GatewayFilterChain chain = exchange -> {
            forwardedExchange.set(exchange);
            return reactor.core.publisher.Mono.empty();
        };

        filter.filter(MockServerWebExchange.from(request), chain).block();

        return forwardedExchange.get();
    }

    private String tenantHeader(ServerWebExchange exchange) {
        return exchange.getRequest().getHeaders().getFirst("X-Tenant-ID");
    }

    private String createToken(
            String secret,
            String tenantId,
            Instant expiresAt
    ) throws Exception {
        return createToken(secret, tenantId, expiresAt, "access");
    }

    private String createToken(
            String secret,
            String tenantId,
            Instant expiresAt,
            String tokenType
    ) throws Exception {
        String header = encodeJson(Map.of("alg", "HS256", "typ", "JWT"));
        String payload = encodeJson(Map.of(
                "tenantId", tenantId,
                "role", "EMPLOYEE",
                "permissions", List.of("tickets:create"),
                "exp", expiresAt.getEpochSecond(),
                "tokenType", tokenType
        ));
        String unsignedToken = header + "." + payload;

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"
        ));

        String signature = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        mac.doFinal(unsignedToken.getBytes(StandardCharsets.UTF_8))
                );

        return unsignedToken + "." + signature;
    }

    private String encodeJson(Map<String, Object> value) throws Exception {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(objectMapper.writeValueAsBytes(value));
    }
}

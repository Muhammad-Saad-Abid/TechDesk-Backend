package com.techdesksystem.techdesk.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.techdesksystem.techdesk.gateway.tenant.TenantResolutionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

class GatewayRbacGlobalFilterTests {

    private static final String SECRET =
            "test-secret-that-is-at-least-thirty-two-bytes-long";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GatewayRbacGlobalFilter filter = new GatewayRbacGlobalFilter(
            new GatewayRbacProperties(
                    true,
                    List.of(new GatewayRbacProperties.RouteRule(
                            "/api/users/**",
                            List.of("POST"),
                            List.of("SUPER_ADMIN", "COMPANY_ADMIN")
                    ))
            ),
            new GatewayJwtVerifier(
                    new TenantResolutionProperties(List.of(), SECRET),
                    objectMapper
            ),
            objectMapper
    );

    @Test
    void allowsRequestWhenJwtRoleMatchesRouteRule() throws Exception {
        ServerWebExchange exchange = exchange(
                MockServerHttpRequest
                        .post("http://localhost/api/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer "
                                + token("COMPANY_ADMIN"))
                        .build()
        );
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.filter(exchange, chain(exchange1 -> chainCalled.set(true))).block();

        assertThat(chainCalled).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void rejectsMatchingRouteWithoutValidToken() {
        ServerWebExchange exchange = exchange(
                MockServerHttpRequest
                        .post("http://localhost/api/users")
                        .build()
        );
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.filter(exchange, chain(exchange1 -> chainCalled.set(true))).block();

        assertThat(chainCalled).isFalse();
        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsMatchingRouteWhenRoleIsNotAllowed() throws Exception {
        ServerWebExchange exchange = exchange(
                MockServerHttpRequest
                        .method(HttpMethod.POST, "http://localhost/api/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer "
                                + token("EMPLOYEE"))
                        .build()
        );
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.filter(exchange, chain(exchange1 -> chainCalled.set(true))).block();

        assertThat(chainCalled).isFalse();
        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void ignoresRoutesWithoutMatchingRule() {
        ServerWebExchange exchange = exchange(
                MockServerHttpRequest
                        .get("http://localhost/api/auth/login")
                        .build()
        );
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.filter(exchange, chain(exchange1 -> chainCalled.set(true))).block();

        assertThat(chainCalled).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    private ServerWebExchange exchange(MockServerHttpRequest request) {
        return MockServerWebExchange.from(request);
    }

    private GatewayFilterChain chain(java.util.function.Consumer<ServerWebExchange> action) {
        return exchange -> {
            action.accept(exchange);
            return reactor.core.publisher.Mono.empty();
        };
    }

    private String token(String role) throws Exception {
        String header = encodeJson(Map.of("alg", "HS256", "typ", "JWT"));
        String payload = encodeJson(Map.of(
                "tenantId", "tenant_alpha",
                "role", role,
                "permissions", List.of("users:create"),
                "exp", Instant.now().plusSeconds(300).getEpochSecond(),
                "tokenType", "access"
        ));
        String unsignedToken = header + "." + payload;

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                SECRET.getBytes(StandardCharsets.UTF_8),
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

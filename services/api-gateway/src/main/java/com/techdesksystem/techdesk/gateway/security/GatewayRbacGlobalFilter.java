package com.techdesksystem.techdesk.gateway.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class GatewayRbacGlobalFilter implements GlobalFilter, Ordered {

    private final GatewayRbacProperties properties;
    private final GatewayJwtVerifier jwtVerifier;
    private final ObjectMapper objectMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public GatewayRbacGlobalFilter(
            GatewayRbacProperties properties,
            GatewayJwtVerifier jwtVerifier,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.jwtVerifier = jwtVerifier;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!properties.enabled()) {
            return chain.filter(exchange);
        }

        Optional<GatewayRbacProperties.RouteRule> matchingRule =
                matchingRule(exchange);
        if (matchingRule.isEmpty()) {
            return chain.filter(exchange);
        }

        Optional<VerifiedJwtClaims> claims =
                jwtVerifier.verifyAccessToken(exchange.getRequest().getHeaders());
        if (claims.isEmpty() || claims.get().role() == null) {
            return reject(
                    exchange,
                    HttpStatus.UNAUTHORIZED,
                    "GATEWAY_AUTHENTICATION_REQUIRED",
                    "A valid access token with a role claim is required."
            );
        }

        List<String> allowedRoles = normalizedRoles(matchingRule.get().roles());
        if (!allowedRoles.contains(claims.get().role())) {
            return reject(
                    exchange,
                    HttpStatus.FORBIDDEN,
                    "GATEWAY_ROLE_FORBIDDEN",
                    "The authenticated role is not allowed to access this route."
            );
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    private Optional<GatewayRbacProperties.RouteRule> matchingRule(
            ServerWebExchange exchange
    ) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        HttpMethod method = exchange.getRequest().getMethod();

        return properties.rules().stream()
                .filter(rule -> pathMatcher.match(rule.path(), path))
                .filter(rule -> methodMatches(rule.methods(), method))
                .findFirst();
    }

    private boolean methodMatches(List<String> configuredMethods, HttpMethod method) {
        if (configuredMethods.isEmpty()) {
            return true;
        }

        String requestMethod = method.name();
        return configuredMethods.stream()
                .map(value -> value.toUpperCase(Locale.ROOT))
                .anyMatch(requestMethod::equals);
    }

    private List<String> normalizedRoles(List<String> roles) {
        return roles.stream()
                .filter(role -> role != null && !role.isBlank())
                .map(role -> role.trim().toUpperCase(Locale.ROOT))
                .toList();
    }

    private Mono<Void> reject(
            ServerWebExchange exchange,
            HttpStatus status,
            String code,
            String message
    ) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        byte[] body = errorBody(exchange, status, code, message);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    private byte[] errorBody(
            ServerWebExchange exchange,
            HttpStatus status,
            String code,
            String message
    ) {
        Map<String, Object> body = Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "code", code,
                "message", message,
                "path", exchange.getRequest().getPath().value()
        );

        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException exception) {
            return ("{\"code\":\"" + code + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }
}

package com.techdesksystem.techdesk.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "security.rbac")
public record GatewayRbacProperties(
        boolean enabled,
        List<RouteRule> rules
) {
    public GatewayRbacProperties {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    public record RouteRule(
            String path,
            List<String> methods,
            List<String> roles
    ) {
        public RouteRule {
            methods = methods == null ? List.of() : List.copyOf(methods);
            roles = roles == null ? List.of() : List.copyOf(roles);
        }
    }
}

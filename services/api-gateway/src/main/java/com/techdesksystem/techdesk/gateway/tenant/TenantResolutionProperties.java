package com.techdesksystem.techdesk.gateway.tenant;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tenant.resolution")
public record TenantResolutionProperties(
        List<String> hostSuffixes,
        String jwtHmacSecret
) {
    public TenantResolutionProperties {
        hostSuffixes = hostSuffixes == null ? List.of() : List.copyOf(hostSuffixes);
    }
}
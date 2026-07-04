package com.techdesksystem.techdesk.tenant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tenant.security")
public record TenantSecurityProperties(String jwtSecret) {
}

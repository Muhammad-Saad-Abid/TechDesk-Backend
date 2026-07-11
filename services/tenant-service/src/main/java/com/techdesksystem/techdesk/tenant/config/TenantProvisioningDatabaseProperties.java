package com.techdesksystem.techdesk.tenant.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "tenant.provisioning-database")
public record TenantProvisioningDatabaseProperties(
        @NotBlank String url,
        @NotBlank String username,
        String password,
        @NotBlank
        @Pattern(regexp = "^[a-z][a-z0-9_]{0,62}$")
        String runtimeRole,
        boolean requireLeastPrivilege
) {
}

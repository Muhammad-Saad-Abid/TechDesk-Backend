package com.techdesksystem.techdesk.gateway.tenant;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "tenant.resolution")
public record TenantResolutionProperties(
        List<String> hostSuffixes,
        @NotBlank(message = "must not be blank")
        @MinUtf8Bytes(value = 32, message = "must contain at least 32 UTF-8 bytes")
        String jwtHmacSecret
) {
    public TenantResolutionProperties {
        hostSuffixes = hostSuffixes == null ? List.of() : List.copyOf(hostSuffixes);
    }
}

package com.techdesksystem.techdesk.tenant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "tenant.provisioning")
public record TenantProvisioningProperties(
        String migrationLocation,
        String portalUrlTemplate,
        String invitationUrl,
        Duration invitationTtl,
        Duration staleProvisioningTimeout,
        Duration notificationLease,
        Duration notificationPollDelay,
        Duration recoveryPollDelay,
        String mailFrom
) {
}

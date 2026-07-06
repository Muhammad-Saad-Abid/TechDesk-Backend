package com.techdesksystem.techdesk.auth.tenant;

import java.time.Instant;

public record TenantIsolationViolation(
        String service,
        String expectedTenant,
        String activeSchema,
        String reason,
        String sqlFingerprint,
        Instant occurredAt
) {
}

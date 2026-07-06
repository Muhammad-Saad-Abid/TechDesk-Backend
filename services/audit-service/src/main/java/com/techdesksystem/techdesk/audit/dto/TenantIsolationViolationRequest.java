package com.techdesksystem.techdesk.audit.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record TenantIsolationViolationRequest(
        @NotBlank String service,
        @NotBlank String expectedTenant,
        String activeSchema,
        @NotBlank String reason,
        @NotBlank String sqlFingerprint,
        @NotNull Instant occurredAt
) {
}

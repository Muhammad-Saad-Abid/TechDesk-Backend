package com.techdesksystem.techdesk.tenant.dto;

import com.techdesksystem.techdesk.tenant.entity.Tenant;
import com.techdesksystem.techdesk.tenant.entity.TenantStatus;
import com.techdesksystem.techdesk.tenant.entity.ProvisioningStatus;

import java.time.Instant;
import java.util.UUID;

public record TenantResponse(
        UUID id,
        String name,
        String slug,
        String schemaName,
        TenantStatus status,
        ProvisioningStatus provisioningStatus,
        Instant createdAt,
        Instant updatedAt
) {
    public static TenantResponse from(Tenant tenant) {
        return new TenantResponse(
                tenant.getId(),
                tenant.getName(),
                tenant.getSlug(),
                tenant.getSchemaName(),
                tenant.getStatus(),
                tenant.getProvisioningStatus(),
                tenant.getCreatedAt(),
                tenant.getUpdatedAt()
        );
    }
}

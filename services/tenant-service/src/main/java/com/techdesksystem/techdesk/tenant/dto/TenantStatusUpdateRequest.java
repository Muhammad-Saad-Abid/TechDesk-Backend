package com.techdesksystem.techdesk.tenant.dto;

import com.techdesksystem.techdesk.tenant.entity.TenantStatus;
import jakarta.validation.constraints.NotNull;

public record TenantStatusUpdateRequest(
        @NotNull TenantStatus status
) {
}

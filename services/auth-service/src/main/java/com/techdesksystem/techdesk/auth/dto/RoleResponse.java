package com.techdesksystem.techdesk.auth.dto;

import java.time.Instant;
import java.util.List;

public record RoleResponse(
        Long id,
        String name,
        String displayName,
        String description,
        boolean systemRole,
        List<String> permissions,
        Instant createdAt,
        Instant updatedAt
) {
}

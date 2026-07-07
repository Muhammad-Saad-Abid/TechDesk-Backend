package com.techdesksystem.techdesk.auth.dto;

import java.time.Instant;

public record DepartmentResponse(
        Long id,
        String name,
        String code,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
}

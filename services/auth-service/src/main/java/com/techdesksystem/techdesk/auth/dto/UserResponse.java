package com.techdesksystem.techdesk.auth.dto;

import java.time.Instant;
import java.util.List;

public record UserResponse(
        Long id,
        String email,
        String firstName,
        String lastName,
        String status,
        boolean enabled,
        Long departmentId,
        String departmentName,
        String primaryRole,
        List<String> roles,
        Instant createdAt,
        Instant updatedAt
) {
}

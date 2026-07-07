package com.techdesksystem.techdesk.auth.dto;

public record PermissionResponse(
        Long id,
        String code,
        String category,
        String description
) {
}

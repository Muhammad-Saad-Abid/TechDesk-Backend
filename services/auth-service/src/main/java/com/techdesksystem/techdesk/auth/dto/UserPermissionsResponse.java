package com.techdesksystem.techdesk.auth.dto;

import java.util.List;

public record UserPermissionsResponse(
        Long userId,
        List<String> permissions
) {
}

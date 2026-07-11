package com.techdesksystem.techdesk.gateway.security;

import java.util.List;

public record VerifiedJwtClaims(
        String tenantId,
        String role,
        List<String> permissions
) {
}

package com.techdesksystem.techdesk.tenant.dto;

import java.time.Instant;

public record AdminInvitation(
        Long adminUserId,
        String invitationJti,
        Instant expiresAt
) {
}

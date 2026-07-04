package com.techdesksystem.techdesk.tenant.service;

import com.techdesksystem.techdesk.tenant.entity.TenantNotificationOutbox;

public interface TenantInvitationNotificationGateway {

    void sendInvitation(
            TenantNotificationOutbox event,
            String signedInvitationToken
    );
}

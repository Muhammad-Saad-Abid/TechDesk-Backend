package com.techdesksystem.techdesk.tenant.service;

import com.techdesksystem.techdesk.tenant.entity.TenantNotificationOutbox;
import com.techdesksystem.techdesk.tenant.util.InvitationTokenSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class TenantNotificationOutboxWorker {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(TenantNotificationOutboxWorker.class);

    private final TenantOutboxDeliveryService deliveryService;
    private final TenantInvitationNotificationGateway notificationGateway;
    private final InvitationTokenSigner tokenSigner;

    public TenantNotificationOutboxWorker(
            TenantOutboxDeliveryService deliveryService,
            TenantInvitationNotificationGateway notificationGateway,
            InvitationTokenSigner tokenSigner
    ) {
        this.deliveryService = deliveryService;
        this.notificationGateway = notificationGateway;
        this.tokenSigner = tokenSigner;
    }

    @Scheduled(
            fixedDelayString = "${tenant.provisioning.notification-poll-delay:5s}",
            initialDelayString = "${tenant.provisioning.notification-initial-delay:5s}"
    )
    public void deliverPendingInvitations() {
        List<UUID> eventIds = deliveryService.claimBatch();

        for (UUID eventId : eventIds) {
            TenantNotificationOutbox event = deliveryService.get(eventId);

            if (event == null) {
                continue;
            }

            try {
                String token = tokenSigner.sign(event);
                notificationGateway.sendInvitation(event, token);
                deliveryService.markSent(eventId);
            } catch (Exception exception) {
                deliveryService.markFailed(eventId, exception);
                LOGGER.warn(
                        "Tenant invitation delivery failed for event '{}'; it will be retried.",
                        eventId,
                        exception
                );
            }
        }
    }
}

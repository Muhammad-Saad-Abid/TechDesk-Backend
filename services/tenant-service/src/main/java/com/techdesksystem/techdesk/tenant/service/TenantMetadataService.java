package com.techdesksystem.techdesk.tenant.service;

import com.techdesksystem.techdesk.tenant.dto.AdminInvitation;
import com.techdesksystem.techdesk.tenant.dto.CreateTenantRequest;
import com.techdesksystem.techdesk.tenant.entity.ProvisioningStatus;
import com.techdesksystem.techdesk.tenant.entity.Tenant;
import com.techdesksystem.techdesk.tenant.entity.TenantNotificationOutbox;
import com.techdesksystem.techdesk.tenant.repository.TenantNotificationOutboxRepository;
import com.techdesksystem.techdesk.tenant.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
public class TenantMetadataService {

    private final TenantRepository tenantRepository;
    private final TenantNotificationOutboxRepository outboxRepository;

    public TenantMetadataService(
            TenantRepository tenantRepository,
            TenantNotificationOutboxRepository outboxRepository
    ) {
        this.tenantRepository = tenantRepository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Tenant create(Tenant tenant) {
        return tenantRepository.saveAndFlush(tenant);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Tenant finalizeProvisioning(
            UUID tenantId,
            CreateTenantRequest request,
            AdminInvitation invitation
    ) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow();
        tenant.setProvisioningStatus(ProvisioningStatus.READY);

        TenantNotificationOutbox event = new TenantNotificationOutbox();
        event.setTenantId(tenant.getId());
        event.setEventType(TenantNotificationOutbox.TENANT_ADMIN_INVITED);
        event.setRecipientEmail(
                request.adminEmail().trim().toLowerCase(Locale.ROOT)
        );
        event.setRecipientName(
                request.adminFirstName().trim() + " "
                        + request.adminLastName().trim()
        );
        event.setTenantName(tenant.getName());
        event.setTenantSlug(tenant.getSlug());
        event.setSchemaName(tenant.getSchemaName());
        event.setAdminUserId(invitation.adminUserId());
        event.setInvitationJti(invitation.invitationJti());
        event.setInvitationExpiresAt(invitation.expiresAt());

        outboxRepository.save(event);
        return tenantRepository.saveAndFlush(tenant);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void delete(UUID tenantId) {
        tenantRepository.deleteById(tenantId);
        tenantRepository.flush();
    }
}

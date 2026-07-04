package com.techdesksystem.techdesk.tenant.service;

import com.techdesksystem.techdesk.tenant.config.TenantProvisioningProperties;
import com.techdesksystem.techdesk.tenant.entity.ProvisioningStatus;
import com.techdesksystem.techdesk.tenant.entity.Tenant;
import com.techdesksystem.techdesk.tenant.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class TenantProvisioningRecoveryJob {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(TenantProvisioningRecoveryJob.class);

    private final TenantRepository tenantRepository;
    private final TenantMetadataService metadataService;
    private final TenantSchemaManager schemaManager;
    private final TenantProvisioningProperties properties;

    public TenantProvisioningRecoveryJob(
            TenantRepository tenantRepository,
            TenantMetadataService metadataService,
            TenantSchemaManager schemaManager,
            TenantProvisioningProperties properties
    ) {
        this.tenantRepository = tenantRepository;
        this.metadataService = metadataService;
        this.schemaManager = schemaManager;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${tenant.provisioning.recovery-poll-delay:10m}",
            initialDelayString = "${tenant.provisioning.recovery-initial-delay:10m}"
    )
    public void removeStaleProvisioningAttempts() {
        Instant cutoff = Instant.now().minus(
                properties.staleProvisioningTimeout()
        );
        List<Tenant> staleTenants = tenantRepository
                .findByProvisioningStatusAndCreatedAtBefore(
                        ProvisioningStatus.PROVISIONING,
                        cutoff
                );

        for (Tenant tenant : staleTenants) {
            try {
                schemaManager.dropSchema(tenant.getSchemaName());
                metadataService.delete(tenant.getId());
                LOGGER.warn(
                        "Removed stale tenant provisioning attempt '{}'.",
                        tenant.getId()
                );
            } catch (Exception exception) {
                LOGGER.error(
                        "Unable to clean stale tenant provisioning attempt '{}'.",
                        tenant.getId(),
                        exception
                );
            }
        }
    }
}

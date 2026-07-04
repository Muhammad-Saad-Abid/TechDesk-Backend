package com.techdesksystem.techdesk.tenant.service;

import com.techdesksystem.techdesk.tenant.dto.AdminInvitation;
import com.techdesksystem.techdesk.tenant.dto.CreateTenantRequest;
import com.techdesksystem.techdesk.tenant.dto.TenantResponse;
import com.techdesksystem.techdesk.tenant.entity.ProvisioningStatus;
import com.techdesksystem.techdesk.tenant.entity.Tenant;
import com.techdesksystem.techdesk.tenant.entity.TenantStatus;
import com.techdesksystem.techdesk.tenant.exception.TenantException;
import com.techdesksystem.techdesk.tenant.repository.TenantRepository;
import com.techdesksystem.techdesk.tenant.util.TenantSchemaNameUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class TenantProvisioningService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(TenantProvisioningService.class);

    private final TenantRepository tenantRepository;
    private final TenantMetadataService metadataService;
    private final TenantSchemaManager schemaManager;
    private final TenantSchemaMigrator schemaMigrator;
    private final DefaultTenantAdminProvisioner adminProvisioner;

    public TenantProvisioningService(
            TenantRepository tenantRepository,
            TenantMetadataService metadataService,
            TenantSchemaManager schemaManager,
            TenantSchemaMigrator schemaMigrator,
            DefaultTenantAdminProvisioner adminProvisioner
    ) {
        this.tenantRepository = tenantRepository;
        this.metadataService = metadataService;
        this.schemaManager = schemaManager;
        this.schemaMigrator = schemaMigrator;
        this.adminProvisioner = adminProvisioner;
    }

    public TenantResponse provision(CreateTenantRequest request) {
        String slug = TenantSchemaNameUtil.normalizeSlug(request.slug());
        String schemaName = TenantSchemaNameUtil.toSchemaName(slug);

        if (tenantRepository.existsBySlug(slug)
                || tenantRepository.existsBySchemaName(schemaName)) {
            throw TenantException.conflict();
        }

        Tenant tenant = new Tenant();
        tenant.setName(request.name().trim());
        tenant.setSlug(slug);
        tenant.setSchemaName(schemaName);
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setProvisioningStatus(ProvisioningStatus.PROVISIONING);

        boolean metadataCreated = false;
        boolean schemaCreated = false;

        try {
            tenant = metadataService.create(tenant);
            metadataCreated = true;

            schemaManager.createSchema(schemaName);
            schemaCreated = true;
            schemaMigrator.migrate(schemaName);

            AdminInvitation invitation = adminProvisioner.create(
                    schemaName,
                    request
            );
            tenant = metadataService.finalizeProvisioning(
                    tenant.getId(),
                    request,
                    invitation
            );

            return TenantResponse.from(tenant);
        } catch (DataIntegrityViolationException exception) {
            rollback(tenant, schemaName, metadataCreated, schemaCreated, exception);

            if (!metadataCreated) {
                throw TenantException.conflict();
            }

            LOGGER.error("Tenant provisioning violated a data constraint.", exception);
            throw TenantException.provisioningFailed();
        } catch (Exception exception) {
            rollback(tenant, schemaName, metadataCreated, schemaCreated, exception);
            LOGGER.error("Tenant provisioning failed for slug '{}'.", slug, exception);
            throw TenantException.provisioningFailed();
        }
    }

    private void rollback(
            Tenant tenant,
            String schemaName,
            boolean metadataCreated,
            boolean schemaCreated,
            Exception originalFailure
    ) {
        if (schemaCreated) {
            try {
                schemaManager.dropSchema(schemaName);
            } catch (Exception cleanupFailure) {
                originalFailure.addSuppressed(cleanupFailure);
                LOGGER.error(
                        "Failed to remove schema '{}' during rollback.",
                        schemaName,
                        cleanupFailure
                );
            }
        }

        if (metadataCreated && tenant.getId() != null) {
            try {
                metadataService.delete(tenant.getId());
            } catch (Exception cleanupFailure) {
                originalFailure.addSuppressed(cleanupFailure);
                LOGGER.error(
                        "Failed to remove tenant metadata '{}' during rollback.",
                        tenant.getId(),
                        cleanupFailure
                );
            }
        }
    }
}

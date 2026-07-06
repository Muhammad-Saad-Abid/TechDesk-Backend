package com.techdesksystem.techdesk.tenant.service;

import com.techdesksystem.techdesk.tenant.entity.ProvisioningStatus;
import com.techdesksystem.techdesk.tenant.entity.Tenant;
import com.techdesksystem.techdesk.tenant.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Brings every already-provisioned tenant to the current tenant migration
 * version on startup. Flyway's schema history and locking keep this idempotent
 * and safe when service instances restart or overlap.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantSchemaMigrationRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            TenantSchemaMigrationRunner.class
    );

    private final TenantRepository tenantRepository;
    private final TenantSchemaMigrator schemaMigrator;

    public TenantSchemaMigrationRunner(
            TenantRepository tenantRepository,
            TenantSchemaMigrator schemaMigrator
    ) {
        this.tenantRepository = tenantRepository;
        this.schemaMigrator = schemaMigrator;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        List<Tenant> readyTenants = tenantRepository
                .findAllByProvisioningStatus(ProvisioningStatus.READY);

        for (Tenant tenant : readyTenants) {
            LOGGER.info(
                    "Applying tenant migrations to schema {}.",
                    tenant.getSchemaName()
            );
            schemaMigrator.migrate(tenant.getSchemaName());
        }

        LOGGER.info(
                "Tenant schema migration sweep completed for {} schemas.",
                readyTenants.size()
        );
    }
}

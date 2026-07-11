package com.techdesksystem.techdesk.tenant.service;

import com.techdesksystem.techdesk.tenant.config.TenantProvisioningDatabase;
import com.techdesksystem.techdesk.tenant.config.TenantProvisioningProperties;
import org.flywaydb.core.Flyway;
import org.springframework.stereotype.Service;

@Service
public class TenantSchemaMigrator {

    private final TenantProvisioningDatabase database;
    private final TenantProvisioningProperties properties;
    private final TenantSchemaManager schemaManager;

    public TenantSchemaMigrator(
            TenantProvisioningDatabase database,
            TenantProvisioningProperties properties,
            TenantSchemaManager schemaManager
    ) {
        this.database = database;
        this.properties = properties;
        this.schemaManager = schemaManager;
    }

    public void migrate(String schemaName) {
        Flyway.configure()
                .dataSource(database.dataSource())
                .locations(properties.migrationLocation())
                .schemas(schemaName)
                .defaultSchema(schemaName)
                .createSchemas(false)
                .cleanDisabled(true)
                .load()
                .migrate();
        schemaManager.grantRuntimeAccess(schemaName);
    }
}

package com.techdesksystem.techdesk.tenant.service;

import com.techdesksystem.techdesk.tenant.config.TenantProvisioningProperties;
import org.flywaydb.core.Flyway;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;

@Service
public class TenantSchemaMigrator {

    private final DataSource dataSource;
    private final TenantProvisioningProperties properties;

    public TenantSchemaMigrator(
            DataSource dataSource,
            TenantProvisioningProperties properties
    ) {
        this.dataSource = dataSource;
        this.properties = properties;
    }

    public void migrate(String schemaName) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations(properties.migrationLocation())
                .schemas(schemaName)
                .defaultSchema(schemaName)
                .createSchemas(false)
                .cleanDisabled(true)
                .load()
                .migrate();
    }
}

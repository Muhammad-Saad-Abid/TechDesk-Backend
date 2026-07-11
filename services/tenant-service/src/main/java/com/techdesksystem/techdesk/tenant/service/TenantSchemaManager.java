package com.techdesksystem.techdesk.tenant.service;

import com.techdesksystem.techdesk.tenant.config.TenantProvisioningDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
public class TenantSchemaManager {

    private static final Pattern SAFE_SCHEMA_NAME =
            Pattern.compile("^tenant_[a-z][a-z0-9_]{0,55}$");

    private final TenantProvisioningDatabase database;
    private final JdbcTemplate jdbcTemplate;

    public TenantSchemaManager(TenantProvisioningDatabase database) {
        this.database = database;
        this.jdbcTemplate = database.jdbcTemplate();
    }

    public void createSchema(String schemaName) {
        database.executeInTransaction(() -> {
            jdbcTemplate.execute("CREATE SCHEMA " + quoteSchema(schemaName));
            grantSchemaUsage(schemaName);
        });
    }

    public void dropSchema(String schemaName) {
        jdbcTemplate.execute(
                "DROP SCHEMA IF EXISTS " + quoteSchema(schemaName) + " CASCADE"
        );
    }

    public boolean exists(String schemaName) {
        validate(schemaName);
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = ?)",
                Boolean.class,
                schemaName
        );
        return Boolean.TRUE.equals(exists);
    }

    public void grantRuntimeAccess(String schemaName) {
        String schema = quoteSchema(schemaName);
        String runtimeRole = quoteRole(database.runtimeRole());

        database.executeInTransaction(() -> {
            grantSchemaUsage(schemaName);
            jdbcTemplate.execute(
                    "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA "
                            + schema + " TO " + runtimeRole
            );
            jdbcTemplate.execute(
                    "REVOKE INSERT, UPDATE, DELETE ON " + schema
                            + ".flyway_schema_history FROM " + runtimeRole
            );
            jdbcTemplate.execute(
                    "GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA "
                            + schema + " TO " + runtimeRole
            );
            jdbcTemplate.execute(
                    "REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA "
                            + schema + " FROM PUBLIC"
            );
            jdbcTemplate.execute(
                    "GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA "
                            + schema + " TO " + runtimeRole
            );
        });
    }

    public String qualifiedTable(String schemaName, String tableName) {
        if (tableName == null
                || !tableName.matches("^[a-z][a-z0-9_]*$")) {
            throw new IllegalArgumentException("Unsafe table name.");
        }
        return quoteSchema(schemaName) + ".\"" + tableName + "\"";
    }

    private String quoteSchema(String schemaName) {
        validate(schemaName);
        return "\"" + schemaName + "\"";
    }

    private void grantSchemaUsage(String schemaName) {
        jdbcTemplate.execute(
                "GRANT USAGE ON SCHEMA " + quoteSchema(schemaName)
                        + " TO " + quoteRole(database.runtimeRole())
        );
    }

    private String quoteRole(String role) {
        if (role == null || !role.matches("^[a-z][a-z0-9_]{0,62}$")) {
            throw new IllegalArgumentException("Unsafe database role name.");
        }
        return "\"" + role + "\"";
    }

    private void validate(String schemaName) {
        if (schemaName == null
                || !SAFE_SCHEMA_NAME.matcher(schemaName).matches()) {
            throw new IllegalArgumentException("Unsafe tenant schema name.");
        }
    }
}

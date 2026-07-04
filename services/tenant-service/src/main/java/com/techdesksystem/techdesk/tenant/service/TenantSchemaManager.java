package com.techdesksystem.techdesk.tenant.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
public class TenantSchemaManager {

    private static final Pattern SAFE_SCHEMA_NAME =
            Pattern.compile("^tenant_[a-z][a-z0-9_]{0,55}$");

    private final JdbcTemplate jdbcTemplate;

    public TenantSchemaManager(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void createSchema(String schemaName) {
        jdbcTemplate.execute("CREATE SCHEMA " + quoteSchema(schemaName));
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

    private void validate(String schemaName) {
        if (schemaName == null
                || !SAFE_SCHEMA_NAME.matcher(schemaName).matches()) {
            throw new IllegalArgumentException("Unsafe tenant schema name.");
        }
    }
}

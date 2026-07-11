package com.techdesksystem.techdesk.tenant.config;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.function.Supplier;

@Component
public class TenantProvisioningDatabase {

    private final TenantProvisioningDatabaseProperties properties;
    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final String runtimeDatasourceRole;

    public TenantProvisioningDatabase(
            TenantProvisioningDatabaseProperties properties,
            DataSourceProperties runtimeDatasourceProperties
    ) {
        this.properties = properties;
        this.runtimeDatasourceRole = runtimeDatasourceProperties.getUsername();

        DriverManagerDataSource provisioningDataSource =
                new DriverManagerDataSource();
        provisioningDataSource.setUrl(properties.url());
        provisioningDataSource.setUsername(properties.username());
        provisioningDataSource.setPassword(
                properties.password() == null ? "" : properties.password()
        );

        this.dataSource = provisioningDataSource;
        this.jdbcTemplate = new JdbcTemplate(provisioningDataSource);
        this.transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(provisioningDataSource)
        );

        if (properties.requireLeastPrivilege()) {
            validateRoleSafety();
        }
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public JdbcTemplate jdbcTemplate() {
        return jdbcTemplate;
    }

    public String runtimeRole() {
        return properties.runtimeRole();
    }

    public void executeInTransaction(Runnable operation) {
        transactionTemplate.executeWithoutResult(status -> operation.run());
    }

    public <T> T executeInTransaction(Supplier<T> operation) {
        return transactionTemplate.execute(status -> operation.get());
    }

    private void validateRoleSafety() {
        if (properties.username().equals(properties.runtimeRole())
                || properties.username().equals(runtimeDatasourceRole)) {
            throw new IllegalStateException(
                    "Provisioning and runtime database roles must be distinct."
            );
        }

        var attributes = jdbcTemplate.queryForMap("""
                SELECT current_user,
                       rolsuper,
                       rolcreatedb,
                       rolcreaterole,
                       rolreplication,
                       rolbypassrls,
                       rolcanlogin,
                       rolinherit,
                       has_database_privilege(
                           current_user,
                           current_database(),
                           'CREATE'
                       ) AS can_create_database_objects,
                       has_database_privilege(
                           current_user,
                           current_database(),
                           'TEMPORARY'
                       ) AS can_create_temporary_objects,
                       has_schema_privilege(
                           current_user,
                           'public',
                           'CREATE'
                       ) AS can_create_public_objects,
                       EXISTS (
                           SELECT 1
                           FROM pg_auth_members membership
                           WHERE membership.member = pg_roles.oid
                       ) AS has_role_membership
                FROM pg_roles
                WHERE rolname = current_user
                """);

        boolean unsafe = !properties.username().equals(
                attributes.get("current_user")
        )
                || Boolean.TRUE.equals(attributes.get("rolsuper"))
                || Boolean.TRUE.equals(attributes.get("rolcreatedb"))
                || Boolean.TRUE.equals(attributes.get("rolcreaterole"))
                || Boolean.TRUE.equals(attributes.get("rolreplication"))
                || Boolean.TRUE.equals(attributes.get("rolbypassrls"))
                || !Boolean.TRUE.equals(attributes.get("rolcanlogin"))
                || Boolean.TRUE.equals(attributes.get("rolinherit"))
                || !Boolean.TRUE.equals(
                        attributes.get("can_create_database_objects")
                )
                || Boolean.TRUE.equals(
                        attributes.get("can_create_temporary_objects")
                )
                || Boolean.TRUE.equals(attributes.get("can_create_public_objects"))
                || Boolean.TRUE.equals(attributes.get("has_role_membership"));

        if (unsafe) {
            throw new IllegalStateException(
                    "Refusing privileged tenant provisioning database role: "
                            + attributes.get("current_user")
            );
        }
    }
}

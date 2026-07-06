package com.techdesksystem.techdesk.auth.tenant;

import com.techdesksystem.techdesk.auth.config.AuthMultitenancyProperties;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

@Component
public class SchemaMultiTenantConnectionProvider
        implements MultiTenantConnectionProvider<String> {

    private static final String PUBLIC_SCHEMA = "public";
    private static final String ACTIVE_TENANT_SQL = """
            SELECT EXISTS (
                SELECT 1
                FROM public.tenants
                WHERE schema_name = ?
                  AND status = 'ACTIVE'
                  AND provisioning_status = 'READY'
            )
            """;
    private static final String MIGRATION_VERSION_SQL = """
            SELECT EXISTS (
                SELECT 1
                FROM flyway_schema_history
                WHERE version = ?
                  AND success = TRUE
            )
            """;

    private final DataSource dataSource;
    private final TenantIdentifierValidator validator;
    private final TenantIsolationViolationReporter violationReporter;
    private final AuthMultitenancyProperties properties;

    public SchemaMultiTenantConnectionProvider(
            DataSource dataSource,
            TenantIdentifierValidator validator,
            TenantIsolationViolationReporter violationReporter,
            AuthMultitenancyProperties properties
    ) {
        this.dataSource = dataSource;
        this.validator = validator;
        this.violationReporter = violationReporter;
        this.properties = properties;
    }

    @Override
    public Connection getAnyConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void releaseAnyConnection(Connection connection) throws SQLException {
        connection.close();
    }

    @Override
    public Connection getConnection(String tenantIdentifier) throws SQLException {
        String tenant = validator.requireValid(tenantIdentifier);
        Connection connection = dataSource.getConnection();
        boolean ready = false;

        try {
            requireActiveReadyTenant(connection, tenant);
            connection.setSchema(tenant);
            if (!tenant.equals(connection.getSchema())) {
                throw new TenantIsolationException(
                        "PostgreSQL did not activate the requested tenant schema."
                );
            }
            requireCurrentMigrationVersion(connection);
            ready = true;
            return TenantSchemaConnectionGuard.protect(
                    connection,
                    tenant,
                    violationReporter
            );
        } finally {
            if (!ready) {
                resetAndClose(connection);
            }
        }
    }

    @Override
    public void releaseConnection(
            String tenantIdentifier,
            Connection connection
    ) throws SQLException {
        Connection delegate = TenantSchemaConnectionGuard.unwrap(connection);
        resetAndClose(delegate);
    }

    @Override
    public boolean supportsAggressiveRelease() {
        return false;
    }

    @Override
    public boolean isUnwrappableAs(Class<?> unwrapType) {
        return unwrapType.isAssignableFrom(getClass())
                || unwrapType.isAssignableFrom(DataSource.class);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> unwrapType) {
        if (unwrapType.isAssignableFrom(getClass())) {
            return (T) this;
        }
        if (unwrapType.isAssignableFrom(DataSource.class)) {
            return (T) dataSource;
        }
        throw new IllegalArgumentException(
                "Cannot unwrap connection provider as " + unwrapType.getName()
        );
    }

    private void requireActiveReadyTenant(
            Connection connection,
            String tenant
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                ACTIVE_TENANT_SQL
        )) {
            statement.setString(1, tenant);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    throw new TenantIsolationException(
                            "Tenant is missing, suspended, or not fully provisioned."
                    );
                }
            }
        }
    }

    private void resetAndClose(Connection connection) throws SQLException {
        SQLException resetFailure = null;
        try {
            if (!connection.isClosed()) {
                connection.setSchema(PUBLIC_SCHEMA);
            }
        } catch (SQLException exception) {
            resetFailure = exception;
        }

        try {
            connection.close();
        } catch (SQLException closeFailure) {
            if (resetFailure != null) {
                resetFailure.addSuppressed(closeFailure);
            } else {
                resetFailure = closeFailure;
            }
        }

        if (resetFailure != null) {
            throw resetFailure;
        }
    }

    private void requireCurrentMigrationVersion(Connection connection)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                MIGRATION_VERSION_SQL
        )) {
            statement.setString(1, properties.getMinimumSchemaVersion());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    throw new TenantIsolationException(
                            "Tenant schema has not reached the required migration version."
                    );
                }
            }
        }
    }
}

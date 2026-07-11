package com.techdesksystem.techdesk.auth.tenant;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RbacRowLevelSecurityIntegrationTests {

    private static final String TENANT = "tenant_rls";
    private static final String APP_USER = "techdesk_app";
    private static final String APP_PASSWORD = "techdesk_app_password";

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("techdesk_rls_test")
                    .withUsername("techdesk")
                    .withPassword("techdesk_test_password")
                    .withStartupTimeout(Duration.ofMinutes(2));

    private static final Long EMPLOYEE_ID;

    static {
        POSTGRES.start();
        initializeDatabase();
        EMPLOYEE_ID = seedUsers();
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @Test
    void unauthenticatedSessionCannotReadSensitiveUsersTable()
            throws Exception {
        try (Connection connection = connection()) {
            connection.setSchema(TENANT);

            assertThat(readEmails(connection)).isEmpty();
        }
    }

    @Test
    void authenticatedUserCanReadOwnUserRowWithoutUsersReadPermission()
            throws Exception {
        try (Connection connection = connection()) {
            connection.setSchema(TENANT);
            setRbacContext(
                    connection,
                    EMPLOYEE_ID,
                    "EMPLOYEE",
                    "notifications:read"
            );

            assertThat(readEmails(connection))
                    .containsExactly("employee@example.com");
        }
    }

    @Test
    void usersReadPermissionCanReadAllUsers() throws Exception {
        try (Connection connection = connection()) {
            connection.setSchema(TENANT);
            setRbacContext(
                    connection,
                    EMPLOYEE_ID,
                    "COMPANY_ADMIN",
                    "users:read"
            );

            assertThat(readEmails(connection))
                    .contains(
                            "admin@example.com",
                            "employee@example.com"
                    );
        }
    }

    @Test
    void userInsertRequiresCreateOrInvitePermission() throws Exception {
        try (Connection denied = connection()) {
            denied.setSchema(TENANT);
            setRbacContext(
                    denied,
                    EMPLOYEE_ID,
                    "EMPLOYEE",
                    "notifications:read"
            );

            assertThatThrownBy(() -> insertUser(
                    denied,
                    "denied@example.com",
                    "EMPLOYEE",
                    "Denied"
            )).isInstanceOf(Exception.class);
        }

        try (Connection allowed = connection()) {
            allowed.setSchema(TENANT);
            setRbacContext(
                    allowed,
                    EMPLOYEE_ID,
                    "COMPANY_ADMIN",
                    "users:invite"
            );

            insertUser(
                    allowed,
                    "invited@example.com",
                    "EMPLOYEE",
                    "Invited"
            );
            setRbacContext(
                    allowed,
                    EMPLOYEE_ID,
                    "COMPANY_ADMIN",
                    "users:read"
            );

            assertThat(readEmails(allowed))
                    .contains("invited@example.com");
        }
    }

    private static void initializeDatabase() {
        migrate("public", publicMigrationLocation());

        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + TENANT);
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }

        migrate(TENANT, tenantMigrationLocation());

        try (Connection connection = adminConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO public.tenants (
                         name, slug, schema_name, status, provisioning_status
                     ) VALUES ('RLS Ltd', 'rls', ?, 'ACTIVE', 'READY')
                     """)) {
            statement.setString(1, TENANT);
            statement.executeUpdate();
            createAndGrantAppRole(connection);
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Long seedUsers() {
        try (Connection connection = adminConnection()) {
            connection.setSchema(TENANT);
            setSystemRbacContext(connection);
            insertUser(connection, "admin@example.com", "COMPANY_ADMIN", "Admin");
            return insertUser(
                    connection,
                    "employee@example.com",
                    "EMPLOYEE",
                    "Employee"
            );
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Long insertUser(
            Connection connection,
            String email,
            String role,
            String firstName
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO auth_users (
                    email, password_hash, first_name, last_name, role, enabled, status
                ) VALUES (?, 'not-used', ?, 'User', ?, TRUE, 'ACTIVE')
                RETURNING id
                """)) {
            statement.setString(1, email);
            statement.setString(2, firstName);
            statement.setString(3, role);

            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static List<String> readEmails(Connection connection)
            throws Exception {
        List<String> emails = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             var result = statement.executeQuery(
                     "SELECT email FROM auth_users ORDER BY email"
             )) {
            while (result.next()) {
                emails.add(result.getString(1));
            }
        }
        return emails;
    }

    private static void setSystemRbacContext(Connection connection)
            throws Exception {
        setConfig(connection, "techdesk.rbac.system", "true");
        setConfig(connection, "techdesk.rbac.user_id", "");
        setConfig(connection, "techdesk.rbac.role", "");
        setConfig(connection, "techdesk.rbac.permissions", "");
    }

    private static void setRbacContext(
            Connection connection,
            Long userId,
            String role,
            String permissions
    ) throws Exception {
        setConfig(connection, "techdesk.rbac.system", "false");
        setConfig(connection, "techdesk.rbac.user_id", userId.toString());
        setConfig(connection, "techdesk.rbac.role", role);
        setConfig(connection, "techdesk.rbac.permissions", permissions);
    }

    private static void setConfig(
            Connection connection,
            String key,
            String value
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config(?, ?, false)"
        )) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeQuery();
        }
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                APP_USER,
                APP_PASSWORD
        );
    }

    private static Connection adminConnection() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
    }

    private static void migrate(String schema, String location) {
        Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword()
                )
                .schemas(schema)
                .defaultSchema(schema)
                .locations(location)
                .load()
                .migrate();
    }

    private static void createAndGrantAppRole(Connection connection)
            throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    DO $$
                    BEGIN
                        IF NOT EXISTS (
                            SELECT 1 FROM pg_roles WHERE rolname = 'techdesk_app'
                        ) THEN
                            CREATE ROLE techdesk_app LOGIN PASSWORD 'techdesk_app_password';
                        END IF;
                    END
                    $$;
                    """);
            statement.execute("GRANT CONNECT ON DATABASE "
                    + POSTGRES.getDatabaseName() + " TO " + APP_USER);
            statement.execute("GRANT USAGE ON SCHEMA " + TENANT
                    + " TO " + APP_USER);
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES "
                    + "IN SCHEMA " + TENANT + " TO " + APP_USER);
            statement.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA "
                    + TENANT + " TO " + APP_USER);
            statement.execute("GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA "
                    + TENANT + " TO " + APP_USER);
        }
    }

    private static String publicMigrationLocation() {
        return filesystemLocation("public");
    }

    private static String tenantMigrationLocation() {
        return filesystemLocation("tenant");
    }

    private static String filesystemLocation(String migrationGroup) {
        Path path = Path.of(
                "..",
                "..",
                "infrastructure",
                "db",
                "migration",
                migrationGroup
        ).toAbsolutePath().normalize();
        return "filesystem:" + path.toString().replace('\\', '/');
    }
}

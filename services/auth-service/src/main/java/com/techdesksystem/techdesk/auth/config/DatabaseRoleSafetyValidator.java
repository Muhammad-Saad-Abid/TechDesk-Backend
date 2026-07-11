package com.techdesksystem.techdesk.auth.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "techdesk.database.require-least-privilege",
        havingValue = "true"
)
public class DatabaseRoleSafetyValidator implements ApplicationRunner {

    private static final String ROLE_QUERY = """
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
            """;

    private final JdbcTemplate jdbcTemplate;
    private final String expectedRole;

    public DatabaseRoleSafetyValidator(
            JdbcTemplate jdbcTemplate,
            DataSourceProperties dataSourceProperties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.expectedRole = dataSourceProperties.getUsername();
    }

    @Override
    public void run(ApplicationArguments arguments) {
        DatabaseRole role = jdbcTemplate.queryForObject(
                ROLE_QUERY,
                (result, rowNumber) -> new DatabaseRole(
                        result.getString("current_user"),
                        result.getBoolean("rolsuper"),
                        result.getBoolean("rolcreatedb"),
                        result.getBoolean("rolcreaterole"),
                        result.getBoolean("rolreplication"),
                        result.getBoolean("rolbypassrls"),
                        result.getBoolean("rolcanlogin"),
                        result.getBoolean("rolinherit"),
                        result.getBoolean("can_create_database_objects"),
                        result.getBoolean("can_create_temporary_objects"),
                        result.getBoolean("can_create_public_objects"),
                        result.getBoolean("has_role_membership")
                )
        );

        if (role == null || role.isUnsafe(expectedRole)) {
            throw new IllegalStateException(
                    "Refusing to start with a privileged database role: "
                            + (role == null ? "unknown" : role.name())
            );
        }
    }

    private record DatabaseRole(
            String name,
            boolean superuser,
            boolean createDatabase,
            boolean createRole,
            boolean replication,
            boolean bypassRls,
            boolean canLogin,
            boolean inheritsPrivileges,
            boolean canCreateDatabaseObjects,
            boolean canCreateTemporaryObjects,
            boolean canCreatePublicObjects,
            boolean hasRoleMembership
    ) {
        boolean isUnsafe(String expectedRole) {
            return !name.equals(expectedRole)
                    || superuser
                    || createDatabase
                    || createRole
                    || replication
                    || bypassRls
                    || !canLogin
                    || inheritsPrivileges
                    || canCreateDatabaseObjects
                    || canCreateTemporaryObjects
                    || canCreatePublicObjects
                    || hasRoleMembership;
        }
    }
}

package com.techdesksystem.techdesk.tenant.service;

import com.techdesksystem.techdesk.tenant.config.TenantProvisioningDatabase;
import com.techdesksystem.techdesk.tenant.config.TenantProvisioningProperties;
import com.techdesksystem.techdesk.tenant.dto.AdminInvitation;
import com.techdesksystem.techdesk.tenant.dto.CreateTenantRequest;
import com.techdesksystem.techdesk.tenant.util.SecureTokenUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.Locale;
import java.util.UUID;

@Service
public class DefaultTenantAdminProvisioner {

    private final TenantProvisioningDatabase database;
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final TenantSchemaManager schemaManager;
    private final TenantProvisioningProperties properties;

    public DefaultTenantAdminProvisioner(
            TenantProvisioningDatabase database,
            PasswordEncoder passwordEncoder,
            TenantSchemaManager schemaManager,
            TenantProvisioningProperties properties
    ) {
        this.database = database;
        this.jdbcTemplate = database.jdbcTemplate();
        this.passwordEncoder = passwordEncoder;
        this.schemaManager = schemaManager;
        this.properties = properties;
    }

    public AdminInvitation create(
            String schemaName,
            CreateTenantRequest request
    ) {
        return database.executeInTransaction(
                () -> createInTransaction(schemaName, request)
        );
    }

    private AdminInvitation createInTransaction(
            String schemaName,
            CreateTenantRequest request
    ) {
        String usersTable = schemaManager.qualifiedTable(
                schemaName,
                "auth_users"
        );
        String invitationsTable = schemaManager.qualifiedTable(
                schemaName,
                "user_invitation_tokens"
        );
        enableSystemRbacContext(schemaName);

        String disabledPassword = passwordEncoder.encode(
                SecureTokenUtil.randomToken()
        );
        String userSql = "INSERT INTO "
                + usersTable
                + " (email, password_hash, first_name, last_name, role, "
                + "enabled, status)"
                + " VALUES (?, ?, ?, ?, 'COMPANY_ADMIN', FALSE, 'INVITED') "
                + "RETURNING id";
        Long userId = jdbcTemplate.queryForObject(
                userSql,
                Long.class,
                request.adminEmail().trim().toLowerCase(Locale.ROOT),
                disabledPassword,
                request.adminFirstName().trim(),
                request.adminLastName().trim()
        );

        String invitationJti = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plus(properties.invitationTtl());
        String invitationSql = "INSERT INTO "
                + invitationsTable
                + " (user_id, jti_hash, expires_at) VALUES (?, ?, ?)";
        jdbcTemplate.update(
                invitationSql,
                userId,
                SecureTokenUtil.sha256(invitationJti),
                Timestamp.from(expiresAt)
        );

        return new AdminInvitation(userId, invitationJti, expiresAt);
    }

    private void enableSystemRbacContext(String schemaName) {
        jdbcTemplate.queryForObject(
                "SELECT set_config(?, ?, true)",
                String.class,
                "techdesk.rbac.system",
                "true"
        );
        jdbcTemplate.queryForObject(
                "SELECT set_config(?, ?, true)",
                String.class,
                "search_path",
                schemaName
        );
    }
}

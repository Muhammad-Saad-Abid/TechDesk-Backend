package com.techdesksystem.techdesk.tenant.service;

import com.techdesksystem.techdesk.tenant.config.TenantProvisioningProperties;
import com.techdesksystem.techdesk.tenant.dto.AdminInvitation;
import com.techdesksystem.techdesk.tenant.dto.CreateTenantRequest;
import com.techdesksystem.techdesk.tenant.util.SecureTokenUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.Locale;
import java.util.UUID;

@Service
public class DefaultTenantAdminProvisioner {

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final TenantSchemaManager schemaManager;
    private final TenantProvisioningProperties properties;

    public DefaultTenantAdminProvisioner(
            JdbcTemplate jdbcTemplate,
            PasswordEncoder passwordEncoder,
            TenantSchemaManager schemaManager,
            TenantProvisioningProperties properties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.schemaManager = schemaManager;
        this.properties = properties;
    }

    @Transactional
    public AdminInvitation create(
            String schemaName,
            CreateTenantRequest request
    ) {
        String disabledPassword = passwordEncoder.encode(
                SecureTokenUtil.randomToken()
        );
        String userSql = "INSERT INTO "
                + schemaManager.qualifiedTable(schemaName, "auth_users")
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
                + schemaManager.qualifiedTable(
                        schemaName,
                        "user_invitation_tokens"
                )
                + " (user_id, jti_hash, expires_at) VALUES (?, ?, ?)";
        jdbcTemplate.update(
                invitationSql,
                userId,
                SecureTokenUtil.sha256(invitationJti),
                Timestamp.from(expiresAt)
        );

        return new AdminInvitation(userId, invitationJti, expiresAt);
    }
}

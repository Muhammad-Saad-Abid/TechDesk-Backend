package com.techdesksystem.techdesk.audit.repository;

import com.techdesksystem.techdesk.audit.dto.TenantIsolationViolationRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TenantIsolationAuditRepository {

    private static final String INSERT_SQL = """
            INSERT INTO public.audit_logs (
                tenant_id,
                action,
                entity_type,
                entity_id,
                new_value,
                created_at
            ) VALUES (
                (SELECT id FROM public.tenants WHERE schema_name = ?),
                'TENANT_ISOLATION_VIOLATION',
                'TENANT_SCHEMA',
                ?,
                jsonb_build_object(
                    'service', ?,
                    'activeSchema', ?,
                    'reason', ?,
                    'sqlFingerprint', ?
                ),
                ?
            )
            """;

    private final JdbcTemplate jdbcTemplate;

    public TenantIsolationAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(TenantIsolationViolationRequest request) {
        jdbcTemplate.update(
                INSERT_SQL,
                request.expectedTenant(),
                request.expectedTenant(),
                request.service(),
                request.activeSchema(),
                request.reason(),
                request.sqlFingerprint(),
                request.occurredAt()
        );
    }
}

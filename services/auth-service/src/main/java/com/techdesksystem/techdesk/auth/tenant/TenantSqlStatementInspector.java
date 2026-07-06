package com.techdesksystem.techdesk.auth.tenant;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TenantSqlStatementInspector implements StatementInspector {

    private static final Pattern EXPLICIT_TENANT_SCHEMA = Pattern.compile(
            "(?i)(tenant_[a-z][a-z0-9_]{0,55})\\s*\\."
    );

    private final TenantIsolationViolationReporter reporter;

    public TenantSqlStatementInspector(
            TenantIsolationViolationReporter reporter
    ) {
        this.reporter = reporter;
    }

    @Override
    public String inspect(String sql) {
        String expectedTenant = TenantContext.requireTenant();
        String normalized = sql.stripLeading().toLowerCase(Locale.ROOT);

        if (normalized.startsWith("set search_path")
                || normalized.startsWith("set schema")) {
            reject(expectedTenant, "SQL attempted to change the active schema.", sql);
        }

        Matcher matcher = EXPLICIT_TENANT_SCHEMA.matcher(sql);
        while (matcher.find()) {
            String referencedTenant = matcher.group(1).toLowerCase(Locale.ROOT);
            if (!expectedTenant.equals(referencedTenant)) {
                reject(
                        expectedTenant,
                        "SQL explicitly referenced a different tenant schema.",
                        sql
                );
            }
        }
        return sql;
    }

    private void reject(String expectedTenant, String reason, String sql) {
        reporter.report(new TenantIsolationViolation(
                "auth-service",
                expectedTenant,
                "unknown",
                reason,
                fingerprint(sql),
                Instant.now()
        ));
        throw new TenantIsolationException(reason);
    }

    private String fingerprint(String sql) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sql.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }
}

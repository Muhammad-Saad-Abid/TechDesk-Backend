package com.techdesksystem.techdesk.auth.tenant;

import com.techdesksystem.techdesk.auth.config.AuthMultitenancyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class AuditServiceTenantIsolationViolationReporter
        implements TenantIsolationViolationReporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            AuditServiceTenantIsolationViolationReporter.class
    );
    private static final String INTERNAL_KEY_HEADER = "X-Internal-Key";

    private final RestClient restClient;
    private final AuthMultitenancyProperties properties;

    public AuditServiceTenantIsolationViolationReporter(
            RestClient.Builder restClientBuilder,
            AuthMultitenancyProperties properties
    ) {
        this.properties = properties;
        this.restClient = restClientBuilder
                .baseUrl(properties.getAuditServiceUrl())
                .build();
    }

    @Override
    public void report(TenantIsolationViolation violation) {
        LOGGER.error(
                "Tenant isolation violation: expected={}, active={}, reason={}, sqlFingerprint={}",
                violation.expectedTenant(),
                violation.activeSchema(),
                violation.reason(),
                violation.sqlFingerprint()
        );

        if (properties.getAuditServiceUrl() == null
                || properties.getAuditServiceUrl().isBlank()
                || properties.getAuditInternalKey() == null
                || properties.getAuditInternalKey().isBlank()) {
            LOGGER.warn("Audit Service reporting is not configured.");
            return;
        }

        try {
            restClient.post()
                    .uri("/internal/audit/tenant-isolation-violations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(INTERNAL_KEY_HEADER, properties.getAuditInternalKey())
                    .body(violation)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException exception) {
            // The original isolation failure must always win. Audit delivery
            // failure is logged separately and never permits the SQL operation.
            LOGGER.error("Could not report tenant violation to Audit Service.", exception);
        }
    }
}

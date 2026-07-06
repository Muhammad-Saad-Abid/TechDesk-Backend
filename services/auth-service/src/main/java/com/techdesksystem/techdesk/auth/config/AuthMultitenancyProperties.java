package com.techdesksystem.techdesk.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "auth.multitenancy")
public class AuthMultitenancyProperties {

    private boolean enabled = true;
    private String auditServiceUrl;
    private String auditInternalKey;
    private String minimumSchemaVersion = "2";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAuditServiceUrl() {
        return auditServiceUrl;
    }

    public void setAuditServiceUrl(String auditServiceUrl) {
        this.auditServiceUrl = auditServiceUrl;
    }

    public String getAuditInternalKey() {
        return auditInternalKey;
    }

    public void setAuditInternalKey(String auditInternalKey) {
        this.auditInternalKey = auditInternalKey;
    }

    public String getMinimumSchemaVersion() {
        return minimumSchemaVersion;
    }

    public void setMinimumSchemaVersion(String minimumSchemaVersion) {
        this.minimumSchemaVersion = minimumSchemaVersion;
    }
}

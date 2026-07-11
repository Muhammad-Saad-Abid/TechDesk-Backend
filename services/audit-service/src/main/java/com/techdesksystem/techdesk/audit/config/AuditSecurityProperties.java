package com.techdesksystem.techdesk.audit.config;

import com.techdesksystem.techdesk.audit.util.validation.MinimumUtf8Bytes;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "audit")
public class AuditSecurityProperties {

    @NotBlank(message = "audit.internal-key must not be blank")
    @MinimumUtf8Bytes(
            value = 32,
            message = "audit.internal-key must contain at least {value} UTF-8 bytes"
    )
    private String internalKey;

    public String getInternalKey() {
        return internalKey;
    }

    public void setInternalKey(String internalKey) {
        this.internalKey = internalKey;
    }
}

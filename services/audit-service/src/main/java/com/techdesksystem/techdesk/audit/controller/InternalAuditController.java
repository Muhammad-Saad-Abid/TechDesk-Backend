package com.techdesksystem.techdesk.audit.controller;

import com.techdesksystem.techdesk.audit.config.AuditSecurityProperties;
import com.techdesksystem.techdesk.audit.dto.TenantIsolationViolationRequest;
import com.techdesksystem.techdesk.audit.service.TenantIsolationAuditService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/internal/audit")
public class InternalAuditController {

    private static final String INTERNAL_KEY_HEADER = "X-Internal-Key";

    private final AuditSecurityProperties properties;
    private final TenantIsolationAuditService auditService;

    public InternalAuditController(
            AuditSecurityProperties properties,
            TenantIsolationAuditService auditService
    ) {
        this.properties = properties;
        this.auditService = auditService;
    }

    @PostMapping("/tenant-isolation-violations")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recordTenantIsolationViolation(
            @RequestHeader(INTERNAL_KEY_HEADER) String suppliedKey,
            @Valid @RequestBody TenantIsolationViolationRequest request
    ) {
        requireValidInternalKey(suppliedKey);
        auditService.record(request);
    }

    private void requireValidInternalKey(String suppliedKey) {
        String configuredKey = properties.getInternalKey();
        if (configuredKey == null || configuredKey.isBlank()
                || !MessageDigest.isEqual(
                        configuredKey.getBytes(StandardCharsets.UTF_8),
                        suppliedKey.getBytes(StandardCharsets.UTF_8)
                )) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Invalid internal service credential."
            );
        }
    }
}

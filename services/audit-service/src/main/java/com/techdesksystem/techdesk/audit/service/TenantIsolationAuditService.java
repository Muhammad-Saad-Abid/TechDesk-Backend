package com.techdesksystem.techdesk.audit.service;

import com.techdesksystem.techdesk.audit.dto.TenantIsolationViolationRequest;
import com.techdesksystem.techdesk.audit.repository.TenantIsolationAuditRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantIsolationAuditService {

    private final TenantIsolationAuditRepository repository;

    public TenantIsolationAuditService(TenantIsolationAuditRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(TenantIsolationViolationRequest request) {
        repository.save(request);
    }
}

package com.techdesksystem.techdesk.auth.service;

import com.techdesksystem.techdesk.auth.entity.AuditLog;
import com.techdesksystem.techdesk.auth.repository.AuditLogRepository;
import org.springframework.stereotype.Service;

@Service
public class SecurityAuditService {

    public static final String REFRESH_TOKEN_REUSE_DETECTED =
            "REFRESH_TOKEN_REUSE_DETECTED";
    public static final String PASSWORD_RESET_COMPLETED =
            "PASSWORD_RESET_COMPLETED";

    private final AuditLogRepository auditLogRepository;

    public SecurityAuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Records a refresh-token replay against the affected account. The event
     * participates in the caller's independent revocation transaction so the
     * revocations and security evidence either both commit or both roll back.
     *
     * @param userId database identifier of the affected user
     */
    public void recordRefreshTokenReplay(Long userId) {
        record(REFRESH_TOKEN_REUSE_DETECTED, userId);
    }

    /**
     * Records successful password recovery for security review.
     *
     * @param userId database identifier of the affected user
     */
    public void recordPasswordReset(Long userId) {
        record(PASSWORD_RESET_COMPLETED, userId);
    }

    private void record(String action, Long userId) {
        AuditLog auditLog = new AuditLog();
        auditLog.setAction(action);
        auditLog.setEntityType("AUTH_USER");
        auditLog.setEntityId(userId.toString());

        auditLogRepository.save(auditLog);
    }
}

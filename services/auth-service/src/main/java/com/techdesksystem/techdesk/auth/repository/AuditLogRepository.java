package com.techdesksystem.techdesk.auth.repository;

import com.techdesksystem.techdesk.auth.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
}

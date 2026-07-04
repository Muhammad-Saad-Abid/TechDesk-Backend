package com.techdesksystem.techdesk.tenant.repository;

import com.techdesksystem.techdesk.tenant.entity.OutboxStatus;
import com.techdesksystem.techdesk.tenant.entity.TenantNotificationOutbox;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TenantNotificationOutboxRepository
        extends JpaRepository<TenantNotificationOutbox, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select event from TenantNotificationOutbox event
            where event.nextAttemptAt <= :now
              and (
                    event.status in :retryableStatuses
                    or (
                        event.status = :processingStatus
                        and event.lockedUntil < :now
                    )
                  )
            order by event.createdAt
            """)
    List<TenantNotificationOutbox> findDeliverableForUpdate(
            @Param("now") Instant now,
            @Param("retryableStatuses") List<OutboxStatus> retryableStatuses,
            @Param("processingStatus") OutboxStatus processingStatus,
            Pageable pageable
    );
}

package com.techdesksystem.techdesk.tenant.service;

import com.techdesksystem.techdesk.tenant.config.TenantProvisioningProperties;
import com.techdesksystem.techdesk.tenant.entity.OutboxStatus;
import com.techdesksystem.techdesk.tenant.entity.TenantNotificationOutbox;
import com.techdesksystem.techdesk.tenant.repository.TenantNotificationOutboxRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class TenantOutboxDeliveryService {

    private static final int BATCH_SIZE = 10;
    private static final Duration MAX_RETRY_DELAY = Duration.ofHours(6);

    private final TenantNotificationOutboxRepository outboxRepository;
    private final TenantProvisioningProperties properties;

    public TenantOutboxDeliveryService(
            TenantNotificationOutboxRepository outboxRepository,
            TenantProvisioningProperties properties
    ) {
        this.outboxRepository = outboxRepository;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<UUID> claimBatch() {
        Instant now = Instant.now();
        List<TenantNotificationOutbox> events = outboxRepository
                .findDeliverableForUpdate(
                        now,
                        List.of(OutboxStatus.PENDING, OutboxStatus.FAILED),
                        OutboxStatus.PROCESSING,
                        PageRequest.of(0, BATCH_SIZE)
                );

        for (TenantNotificationOutbox event : events) {
            event.setStatus(OutboxStatus.PROCESSING);
            event.setAttempts(event.getAttempts() + 1);
            event.setLockedUntil(now.plus(properties.notificationLease()));
            event.setLastError(null);
        }
        outboxRepository.saveAll(events);

        return events.stream().map(TenantNotificationOutbox::getId).toList();
    }

    @Transactional(readOnly = true)
    public TenantNotificationOutbox get(UUID eventId) {
        return outboxRepository.findById(eventId).orElse(null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(UUID eventId) {
        outboxRepository.findById(eventId).ifPresent(event -> {
            event.setStatus(OutboxStatus.SENT);
            event.setSentAt(Instant.now());
            event.setLockedUntil(null);
            event.setLastError(null);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID eventId, Exception failure) {
        outboxRepository.findById(eventId).ifPresent(event -> {
            Duration delay = retryDelay(event.getAttempts());
            String message = failure.getMessage() == null
                    ? failure.getClass().getSimpleName()
                    : failure.getMessage();

            event.setStatus(OutboxStatus.FAILED);
            event.setLockedUntil(null);
            event.setNextAttemptAt(Instant.now().plus(delay));
            event.setLastError(message.substring(0, Math.min(500, message.length())));
        });
    }

    private Duration retryDelay(int attempts) {
        long exponent = Math.min(Math.max(attempts - 1, 0), 8);
        Duration calculated = Duration.ofMinutes(1L << exponent);
        return calculated.compareTo(MAX_RETRY_DELAY) > 0
                ? MAX_RETRY_DELAY
                : calculated;
    }
}
